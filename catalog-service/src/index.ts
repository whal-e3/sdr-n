import { buildCatalog } from "./catalog.ts";
import { gzipJson, signCatalog } from "./crypto.ts";
import { fetchUpstream } from "./upstream.ts";
import type { AttemptState, CatalogPointer, Env } from "./types.ts";

const CURRENT_KEY = "catalog/current.json";
const ATTEMPT_KEY = "catalog/attempt.json";
// CelesTrak permits one download per two-hour update; Space-Track GP allows
// at most one per hour. A shared two-hour attempt gate satisfies both.
const UPSTREAM_MIN_INTERVAL_MS = 2 * 60 * 60 * 1000;
const ANDROID_MAX_UNCOMPRESSED_BYTES = 40_000_000;
const ANDROID_MAX_COMPRESSED_BYTES = 6_000_000;
const SPACETRACK_MIN_DISTINCT_ORBITS = 15_000;

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

interface AttemptClaim {
  attemptedAt: string;
  etag: string;
}

async function claimRefreshAttempt(bucket: R2Bucket, now: Date): Promise<AttemptClaim | null> {
  const previous = await bucket.get(ATTEMPT_KEY);
  const previousAttempt = previous ? JSON.parse(await previous.text()) as AttemptState : null;
  const lastAttemptMs = previousAttempt ? Date.parse(previousAttempt.attemptedAt) : NaN;
  if (Number.isFinite(lastAttemptMs) && now.valueOf() - lastAttemptMs < UPSTREAM_MIN_INTERVAL_MS) {
    return null;
  }

  // Both a first run and a later run can race after reading the same state.
  // R2 checks this precondition atomically; a losing invocation makes no
  // upstream request. If-None-Match: * handles the absent-object case.
  const onlyIf = previous
    ? { etagMatches: previous.etag }
    : new Headers({ "If-None-Match": "*" });
  const attemptedAt = now.toISOString();
  const claimed = await bucket.put(ATTEMPT_KEY, JSON.stringify({ attemptedAt } satisfies AttemptState), {
    httpMetadata: { contentType: "application/json" },
    onlyIf,
  });
  return claimed ? { attemptedAt, etag: claimed.etag } : null;
}

async function finishRefreshAttempt(
  bucket: R2Bucket,
  claim: AttemptClaim,
  result: AttemptState,
): Promise<void> {
  // An old run must not overwrite a newer attempt if its work somehow outlives
  // the two-hour interval.
  await bucket.put(ATTEMPT_KEY, JSON.stringify(result), {
    httpMetadata: { contentType: "application/json" },
    onlyIf: { etagMatches: claim.etag },
  });
}

export async function refreshCatalog(
  env: Env,
  now: Date = new Date(),
  fetcher: typeof fetch = fetch,
  minimumRows = 1_000,
  minimumSpaceTrackDistinctOrbits = SPACETRACK_MIN_DISTINCT_ORBITS,
): Promise<"published" | "skipped"> {
  if (!env.CATALOG_SIGNING_KEY_PKCS8_BASE64 || !env.SIGNING_KEY_ID) {
    throw new Error("Catalog signing secret and key ID must be configured");
  }
  if (Boolean(env.SPACETRACK_IDENTITY?.trim()) !== Boolean(env.SPACETRACK_PASSWORD)) {
    throw new Error("Space-Track identity and password must be configured together");
  }
  // Claim before requesting either source so even a failed publish cannot
  // cause a second download during the same two-hour interval.
  const claim = await claimRefreshAttempt(env.CATALOG_BUCKET, now);
  if (!claim) return "skipped";
  const { attemptedAt } = claim;
  try {
    const spaceTrack = env.SPACETRACK_IDENTITY && env.SPACETRACK_PASSWORD
      ? { identity: env.SPACETRACK_IDENTITY, password: env.SPACETRACK_PASSWORD }
      : undefined;
    const { omm, satellites, transmitters, source } = await fetchUpstream(fetcher, minimumRows, spaceTrack);
    if (omm.length < minimumRows || satellites.length < minimumRows || transmitters.length < minimumRows) {
      throw new Error("Upstream dataset is unexpectedly small; retaining last-known-good catalog");
    }
    const current = await readJson<CatalogPointer>(env.CATALOG_BUCKET, CURRENT_KEY);
    const sequence = (current?.sequence ?? 0) + 1;
    const manifest = buildCatalog(omm, satellites, transmitters, sequence, attemptedAt, source);
    if (source === "space-track") {
      // Check the normalized, deduplicated manifest. A large JSON response can
      // still be partial or repeat the same NORAD IDs many times.
      const count = manifest.satellites.length;
      if (count < minimumSpaceTrackDistinctOrbits) {
        throw new Error(`Space-Track GP has ${count} distinct orbits; minimum ${minimumSpaceTrackDistinctOrbits}`);
      }
      // Counts from a different source do not establish the expected GP ID set.
      // Legacy pointers have no known source. The builder separately retains
      // every distinct ID returned by this unfiltered latest-GP response.
      if (current?.orbitalSource === "space-track" && count < Math.ceil(current.satelliteCount * 0.9)) {
        throw new Error(`Space-Track GP has ${count} distinct orbits, below 90% of the current Space-Track catalog`);
      }
    }
    const bytes = await gzipJson(manifest, ANDROID_MAX_UNCOMPRESSED_BYTES);
    if (bytes.byteLength > ANDROID_MAX_COMPRESSED_BYTES) {
      throw new Error("Catalog exceeds Android compressed size limit");
    }
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
      orbitalSource: source,
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
    await finishRefreshAttempt(env.CATALOG_BUCKET, claim, {
      attemptedAt,
      completedAt: new Date().toISOString(),
      outcome: "published",
    } satisfies AttemptState);
    return "published";
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await finishRefreshAttempt(env.CATALOG_BUCKET, claim, {
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
      orbitalSource: current?.orbitalSource ?? null,
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
