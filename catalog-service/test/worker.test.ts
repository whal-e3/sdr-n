import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import test from "node:test";
import { handleRequest, refreshCatalog } from "../src/index.ts";
import { upstreamRows } from "./fixtures.ts";
import type { CatalogSignature, Env } from "../src/types.ts";

class MemoryBucket {
  private readonly objects = new Map<string, Uint8Array>();

  async get(key: string) {
    const bytes = this.objects.get(key);
    if (!bytes) return null;
    return {
      text: async () => new TextDecoder().decode(bytes),
      arrayBuffer: async () => Uint8Array.from(bytes).buffer,
      httpEtag: '"test-object"',
    };
  }

  async put(key: string, value: string | Uint8Array) {
    this.objects.set(key, typeof value === "string" ? new TextEncoder().encode(value) : Uint8Array.from(value));
    return null;
  }
}

test("publishes verifiable gzip bytes and preserves last-known-good after failed update", async () => {
  const pair = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]) as CryptoKeyPair;
  const pkcs8 = Buffer.from(await webcrypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const bucket = new MemoryBucket();
  const env = {
    CATALOG_BUCKET: bucket as unknown as R2Bucket,
    CATALOG_SIGNING_KEY_PKCS8_BASE64: pkcs8,
    SIGNING_KEY_ID: "test-v1",
  } satisfies Env;
  const rows = upstreamRows();
  let calls = 0;
  const fetcher = (async (input: RequestInfo | URL) => {
    calls++;
    const url = String(input);
    if (url.includes("celestrak.org")) return Response.json(rows.omm);
    if (url.includes("/satellites/")) return Response.json(rows.satellites);
    return Response.json(rows.transmitters);
  }) as typeof fetch;

  const first = new Date("2026-09-23T12:00:00Z");
  assert.equal(await refreshCatalog(env, first, fetcher, 1), "published");
  assert.equal(calls, 3);
  const gzipResponse = await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env);
  assert.equal(gzipResponse.status, 200);
  assert.equal(gzipResponse.headers.get("Content-Type"), "application/gzip");
  assert.equal(gzipResponse.headers.get("Content-Encoding"), null);
  const compressed = await gzipResponse.arrayBuffer();
  const signatureResponse = await handleRequest(new Request("https://catalog.test/v1/catalog.sig?sequence=1"), env);
  const signature = await signatureResponse.json() as CatalogSignature;
  assert.equal(signature.sequence, 1);
  const signatureBytes = Buffer.from(signature.signature.replace(/-/g, "+").replace(/_/g, "/"), "base64");
  assert.equal(await webcrypto.subtle.verify("Ed25519", pair.publicKey, signatureBytes, compressed), true);
  const digest = Buffer.from(await webcrypto.subtle.digest("SHA-256", compressed)).toString("hex");
  assert.equal(signature.sha256, digest);
  const inflated = await new Response(
    new Response(compressed).body!.pipeThrough(new DecompressionStream("gzip")),
  ).json() as { sequence: number; satellites: unknown[] };
  assert.equal(inflated.sequence, 1);
  assert.equal(inflated.satellites.length, 3);

  const cached = await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz", {
    headers: { "If-None-Match": gzipResponse.headers.get("ETag")! },
  }), env);
  assert.equal(cached.status, 304);
  assert.equal(await refreshCatalog(env, new Date("2026-09-23T13:00:00Z"), fetcher, 1), "skipped");
  assert.equal(calls, 3);

  const failingFetcher = (async () => new Response("upstream unavailable", { status: 503 })) as typeof fetch;
  await assert.rejects(refreshCatalog(env, new Date("2026-09-23T14:00:01Z"), failingFetcher, 1));
  const status = await (await handleRequest(new Request("https://catalog.test/v1/status"), env)).json() as {
    available: boolean; sequence: number; lastOutcome: string;
  };
  assert.equal(status.available, true);
  assert.equal(status.sequence, 1);
  assert.equal(status.lastOutcome, "failed");
  assert.equal((await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).status, 200);
});
