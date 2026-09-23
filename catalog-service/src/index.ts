import { buildCatalog } from "./catalog.ts";
import { gzipJson, signCatalog } from "./crypto.ts";
import { fetchUpstream } from "./upstream.ts";
import type { AttemptState, CatalogPointer, Env } from "./types.ts";

const CURRENT_KEY = "catalog/current.json";
const ATTEMPT_KEY = "catalog/attempt.json";
const CELESTRAK_MIN_INTERVAL_MS = 2 * 60 * 60 * 1000;

function releaseKey(sequence: number, suffix: "json.gz" | "sig"): string {
  return `catalog/releases/${sequence}.${suffix}`;
}

async function readJson<T>(bucket: R2Bucket, key: string): Promise<T | null> {
  const object = await bucket.get(key);
  return object ? (JSON.parse(await object.text()) as T) : null;
}

async function writeJson(bucket: R2Bucket, key: string, value: unknown): Promise<void> {
  await bucket.put(key, JSON.stringify(value), { httpMetadata: { contentType: "application/json" } });
}

export async function refreshCatalog(
  env: Env,
  now: Date = new Date(),
  fetcher: typeof fetch = fetch,
  minimumRows = 100,
): Promise<"published" | "skipped"> {
  if (!env.CATALOG_SIGNING_KEY_PKCS8_BASE64 || !env.SIGNING_KEY_ID) {
    throw new Error("Catalog signing secret and key ID must be configured");
  }
  const previousAttempt = await readJson<AttemptState>(env.CATALOG_BUCKET, ATTEMPT_KEY);
  const lastAttemptMs = previousAttempt ? Date.parse(previousAttempt.attemptedAt) : NaN;
  if (Number.isFinite(lastAttemptMs) && now.valueOf() - lastAttemptMs < CELESTRAK_MIN_INTERVAL_MS) {
    return "skipped";
  }

  // Record before requesting CelesTrak so even a failed publish cannot cause
  // a second download during the same two-hour source update interval.
  const attemptedAt = now.toISOString();
  await writeJson(env.CATALOG_BUCKET, ATTEMPT_KEY, { attemptedAt } satisfies AttemptState);
  try {
    const { omm, satellites, transmitters } = await fetchUpstream(fetcher);
    if (omm.length < minimumRows || satellites.length < minimumRows || transmitters.length < minimumRows) {
      throw new Error("Upstream dataset is unexpectedly small; retaining last-known-good catalog");
    }
    const current = await readJson<CatalogPointer>(env.CATALOG_BUCKET, CURRENT_KEY);
    const sequence = (current?.sequence ?? 0) + 1;
    const manifest = buildCatalog(omm, satellites, transmitters, sequence, attemptedAt);
    const bytes = await gzipJson(manifest);
    const signature = await signCatalog(
      bytes,
      env.CATALOG_SIGNING_KEY_PKCS8_BASE64,
      env.SIGNING_KEY_ID,
      sequence,
    );
    const pointer: CatalogPointer = {
      sequence,
      generatedAt: manifest.generatedAt,
      sourceUpdatedAt: manifest.sourceUpdatedAt,
      satelliteCount: manifest.satellites.length,
      transmitterCount: manifest.satellites.reduce((sum, sat) => sum + sat.transmitters.length, 0),
      sha256: signature.sha256,
      keyId: signature.keyId,
    };
    await Promise.all([
      env.CATALOG_BUCKET.put(releaseKey(sequence, "json.gz"), bytes, {
        httpMetadata: { contentType: "application/gzip" },
      }),
      writeJson(env.CATALOG_BUCKET, releaseKey(sequence, "sig"), signature),
    ]);
    // The pointer is the commit point. Incomplete releases are never served.
    await writeJson(env.CATALOG_BUCKET, CURRENT_KEY, pointer);
    await writeJson(env.CATALOG_BUCKET, ATTEMPT_KEY, {
      attemptedAt,
      completedAt: new Date().toISOString(),
      outcome: "published",
    } satisfies AttemptState);
    return "published";
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await writeJson(env.CATALOG_BUCKET, ATTEMPT_KEY, {
      attemptedAt,
      completedAt: new Date().toISOString(),
      outcome: "failed",
      error: message.slice(0, 500),
    } satisfies AttemptState);
    throw error;
  }
}

function requestedSequence(request: Request, current: CatalogPointer): number | null {
  const raw = new URL(request.url).searchParams.get("sequence");
  if (raw === null) return current.sequence;
  if (!/^[1-9]\d{0,8}$/.test(raw)) return null;
  const sequence = Number(raw);
  return sequence <= current.sequence ? sequence : null;
}

async function serveRelease(
  request: Request,
  env: Env,
  suffix: "json.gz" | "sig",
): Promise<Response> {
  const current = await readJson<CatalogPointer>(env.CATALOG_BUCKET, CURRENT_KEY);
  if (!current) return Response.json({ error: "Catalog not yet published" }, { status: 503 });
  const sequence = requestedSequence(request, current);
  if (sequence === null) return Response.json({ error: "Invalid catalog sequence" }, { status: 400 });
  const object = await env.CATALOG_BUCKET.get(releaseKey(sequence, suffix));
  if (!object) return Response.json({ error: "Catalog release unavailable" }, { status: 404 });
  const etag = sequence === current.sequence ? `"${current.sha256}"` : object.httpEtag;
  const headers = new Headers({
    "Content-Type": suffix === "json.gz" ? "application/gzip" : "application/json; charset=utf-8",
    "Cache-Control": "public, max-age=300",
    "X-Catalog-Sequence": String(sequence),
    ETag: etag,
  });
  // No Content-Encoding: clients must verify the exact gzip bytes, then decompress.
  if (request.headers.get("If-None-Match")?.split(",").map((part) => part.trim()).includes(etag)) {
    return new Response(null, { status: 304, headers });
  }
  if (request.method === "HEAD") return new Response(null, { headers });
  return new Response(await object.arrayBuffer(), { headers });
}

async function serveStatus(env: Env): Promise<Response> {
  const [current, attempt] = await Promise.all([
    readJson<CatalogPointer>(env.CATALOG_BUCKET, CURRENT_KEY),
    readJson<AttemptState>(env.CATALOG_BUCKET, ATTEMPT_KEY),
  ]);
  return Response.json(
    {
      available: current !== null,
      sequence: current?.sequence ?? null,
      generatedAt: current?.generatedAt ?? null,
      sourceUpdatedAt: current?.sourceUpdatedAt ?? null,
      satelliteCount: current?.satelliteCount ?? 0,
      transmitterCount: current?.transmitterCount ?? 0,
      keyId: current?.keyId ?? null,
      lastAttemptAt: attempt?.attemptedAt ?? null,
      lastOutcome: attempt?.outcome ?? null,
      lastError: attempt?.error ?? null,
    },
    { headers: { "Cache-Control": "no-store" } },
  );
}

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405, headers: { Allow: "GET, HEAD" } });
  }
  const path = new URL(request.url).pathname;
  if (path === "/v1/catalog.json.gz") return serveRelease(request, env, "json.gz");
  if (path === "/v1/catalog.sig") return serveRelease(request, env, "sig");
  if (path === "/v1/status") {
    if (request.method === "HEAD") return new Response(null, { headers: { "Cache-Control": "no-store" } });
    return serveStatus(env);
  }
  return new Response("Not found", { status: 404 });
}

export default {
  fetch: handleRequest,
  scheduled(_event, env, context) {
    context.waitUntil(refreshCatalog(env));
  },
} satisfies ExportedHandler<Env>;
