import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import test from "node:test";
import { handleRequest, refreshCatalog } from "../src/index.ts";
import { CELESTRAK_OMM_URLS } from "../src/upstream.ts";
import { SPACETRACK_GP_URL } from "../src/spacetrack.ts";
import { upstreamRows } from "./fixtures.ts";
import type { CatalogSignature, Env } from "../src/types.ts";

class MemoryBucket {
  private readonly objects = new Map<string, { bytes: Uint8Array; etag: string }>();
  private version = 0;

  async get(key: string) {
    const object = this.objects.get(key);
    if (!object) return null;
    return {
      text: async () => new TextDecoder().decode(object.bytes),
      arrayBuffer: async () => Uint8Array.from(object.bytes).buffer,
      etag: object.etag,
      httpEtag: `"${object.etag}"`,
    };
  }

  async put(key: string, value: string | Uint8Array, options?: R2PutOptions) {
    const previous = this.objects.get(key);
    const onlyIf = options?.onlyIf;
    if (onlyIf instanceof Headers) {
      if (onlyIf.get("If-None-Match") === "*" && previous) return null;
    } else if (onlyIf?.etagMatches !== undefined && previous?.etag !== onlyIf.etagMatches) {
      return null;
    }
    const etag = `mock-${++this.version}`;
    this.objects.set(key, {
      bytes: typeof value === "string" ? new TextEncoder().encode(value) : Uint8Array.from(value),
      etag,
    });
    return { etag };
  }
}

class PairedAttemptReadBucket extends MemoryBucket {
  private barrier: { remaining: number; release: () => void; ready: Promise<void> } | null = null;

  pauseNextTwoAttemptReads() {
    let release!: () => void;
    const ready = new Promise<void>((resolve) => { release = resolve; });
    this.barrier = { remaining: 2, release, ready };
  }

  override async get(key: string) {
    const result = await super.get(key);
    const barrier = key === "catalog/attempt.json" ? this.barrier : null;
    if (barrier) {
      barrier.remaining--;
      if (barrier.remaining === 0) {
        this.barrier = null;
        barrier.release();
      }
      await barrier.ready;
    }
    return result;
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
    if (url.includes("GROUP=LAST-30-DAYS")) {
      return Response.json([{ ...rows.omm[0], NORAD_CAT_ID: 123_456, OBJECT_NAME: "NEW OBJECT" }]);
    }
    if (url.includes("GROUP=ANALYST")) {
      return Response.json([{ ...rows.omm[0], NORAD_CAT_ID: 80_123, OBJECT_NAME: "ANALYST OBJECT" }]);
    }
    if (url.includes("celestrak.org")) return Response.json(rows.omm);
    if (url.includes("/satellites/")) return Response.json(rows.satellites);
    return Response.json(rows.transmitters);
  }) as typeof fetch;

  const first = new Date("2026-09-23T12:00:00Z");
  assert.equal(await refreshCatalog(env, first, fetcher, 1), "published");
  assert.equal(calls, CELESTRAK_OMM_URLS.length + 2);
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
  assert.equal(inflated.satellites.length, 5);

  const cached = await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz", {
    headers: { "If-None-Match": gzipResponse.headers.get("ETag")! },
  }), env);
  assert.equal(cached.status, 304);
  assert.equal(await refreshCatalog(env, new Date("2026-09-23T13:00:00Z"), fetcher, 1), "skipped");
  assert.equal(calls, CELESTRAK_OMM_URLS.length + 2);

  let failedSourceCalls = 0;
  const failingFetcher = (async () => {
    failedSourceCalls++;
    return new Response("upstream unavailable", { status: 503 });
  }) as typeof fetch;
  await assert.rejects(refreshCatalog(env, new Date("2026-09-23T14:00:01Z"), failingFetcher, 1));
  assert.equal(await refreshCatalog(env, new Date("2026-09-23T14:01:00Z"), failingFetcher, 1), "skipped");
  assert.equal(failedSourceCalls, 1);
  const status = await (await handleRequest(new Request("https://catalog.test/v1/status"), env)).json() as {
    available: boolean; sequence: number; orbitalSource: string; lastOutcome: string;
  };
  assert.equal(status.available, true);
  assert.equal(status.sequence, 1);
  assert.equal(status.orbitalSource, "celestrak");
  assert.equal(status.lastOutcome, "failed");
  assert.equal((await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).status, 200);
});

test("optional Space-Track GP refresh keeps its last signed catalog after an upstream failure", async () => {
  const pair = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]) as CryptoKeyPair;
  const bucket = new MemoryBucket();
  const env = {
    CATALOG_BUCKET: bucket as unknown as R2Bucket,
    CATALOG_SIGNING_KEY_PKCS8_BASE64: Buffer.from(await webcrypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64"),
    SIGNING_KEY_ID: "test-space-track",
    SPACETRACK_IDENTITY: "tester@example.org",
    SPACETRACK_PASSWORD: "test-secret",
  } satisfies Env;
  const rows = upstreamRows();
  Object.assign(rows.omm[1]!, { EPOCH: "2024-01-01T12:00:00Z", DECAY_DATE: "2024-01-02" });
  let failGp = false;
  const requests: string[] = [];
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(url);
    if (url.endsWith("/auth/login") && init?.method !== "POST") {
      return new Response('<input name="spacetrack_csrf_token" value="0123456789abcdef0123456789abcdef">', {
        headers: { "Content-Type": "text/html", "Set-Cookie": "chocolatechip=anonymous; Path=/" },
      });
    }
    if (url.endsWith("/auth/login")) {
      return new Response(null, { status: 302, headers: { Location: "/", "Set-Cookie": "chocolatechip=authenticated; Path=/" } });
    }
    if (url === SPACETRACK_GP_URL) {
      return failGp ? new Response("unavailable", { status: 503 }) : Response.json(rows.omm);
    }
    if (url.includes("/satellites/")) return Response.json(rows.satellites);
    if (url.includes("/transmitters/")) return Response.json(rows.transmitters);
    throw new Error("Unexpected upstream request");
  }) as typeof fetch;

  const first = new Date("2026-09-23T12:00:00Z");
  await assert.rejects(
    refreshCatalog({ ...env, SPACETRACK_PASSWORD: undefined }, first, fetcher, 1, 1),
    /Space-Track identity and password must be configured together/,
  );
  assert.equal(requests.length, 0);
  assert.equal(await refreshCatalog(env, first, fetcher, 1, 1), "published");
  assert.equal(requests.filter((url) => url === SPACETRACK_GP_URL).length, 1);
  assert.equal(requests.some((url) => url.includes("celestrak.org")), false);
  const firstGzip = await (await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).arrayBuffer();
  const firstCatalog = await new Response(new Response(firstGzip).body!.pipeThrough(new DecompressionStream("gzip"))).json() as {
    sequence: number; attribution: string[];
    satellites: { noradId: string; omm: Record<string, unknown> }[];
  };
  assert.equal(firstCatalog.sequence, 1);
  assert.match(firstCatalog.attribution[0]!, /USSPACECOM 18 SDS via Space-Track/);
  assert.deepEqual(firstCatalog.satellites.map((row) => row.noradId), ["25544", "40069", "59051"]);
  assert.equal(firstCatalog.satellites[1]?.omm.EPOCH, "2024-01-01T12:00:00.000Z");
  assert.equal(firstCatalog.satellites[1]?.omm.DECAY_DATE, "2024-01-02");
  assert.equal(await refreshCatalog(env, new Date("2026-09-23T13:00:00Z"), fetcher, 1, 1), "skipped");
  assert.equal(requests.filter((url) => url === SPACETRACK_GP_URL).length, 1);

  failGp = true;
  await assert.rejects(refreshCatalog(env, new Date("2026-09-23T14:00:01Z"), fetcher, 1, 1), /Space-Track GP returned HTTP 503/);
  assert.equal(requests.filter((url) => url === SPACETRACK_GP_URL).length, 2);
  const status = await (await handleRequest(new Request("https://catalog.test/v1/status"), env)).json() as {
    sequence: number; orbitalSource: string; lastOutcome: string; lastError: string;
  };
  assert.equal(status.sequence, 1);
  assert.equal(status.orbitalSource, "space-track");
  assert.equal(status.lastOutcome, "failed");
  assert.doesNotMatch(status.lastError, /test-secret|tester@example\.org/);
  const retained = await (await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).arrayBuffer();
  assert.deepEqual(Buffer.from(retained), Buffer.from(firstGzip));
});

test("Space-Track rejects partial distinct-orbit sets before replacing a signed release", async () => {
  const pair = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]) as CryptoKeyPair;
  const bucket = new MemoryBucket();
  const env = {
    CATALOG_BUCKET: bucket as unknown as R2Bucket,
    CATALOG_SIGNING_KEY_PKCS8_BASE64: Buffer.from(await webcrypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64"),
    SIGNING_KEY_ID: "test-count-guard",
    SPACETRACK_IDENTITY: "tester@example.org",
    SPACETRACK_PASSWORD: "test-secret",
  } satisfies Env;
  const rows = upstreamRows();
  const makeGpRows = (count: number) => Array.from({ length: count }, (_, index) => ({
    ...rows.omm[0],
    NORAD_CAT_ID: 100_000 + index,
    OBJECT_NAME: `TEST OBJECT ${index}`,
  }));
  let gpRows: unknown[] = makeGpRows(20);
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/auth/login") && init?.method !== "POST") {
      return new Response('<input name="spacetrack_csrf_token" value="0123456789abcdef0123456789abcdef">', {
        headers: { "Content-Type": "text/html", "Set-Cookie": "chocolatechip=anonymous; Path=/" },
      });
    }
    if (url.endsWith("/auth/login")) {
      return new Response(null, { status: 302, headers: { Location: "/", "Set-Cookie": "chocolatechip=authenticated; Path=/" } });
    }
    if (url === SPACETRACK_GP_URL) return Response.json(gpRows);
    if (url.includes("/satellites/")) return Response.json(rows.satellites);
    if (url.includes("/transmitters/")) return Response.json(rows.transmitters);
    throw new Error("Unexpected upstream request");
  }) as typeof fetch;

  const first = new Date("2026-09-23T12:00:00Z");
  // The final argument relaxes the 15,000-distinct-orbit floor for small mocks.
  assert.equal(await refreshCatalog(env, first, fetcher, 1, 1), "published");
  const original = await (await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).arrayBuffer();
  gpRows = makeGpRows(17);
  await assert.rejects(
    refreshCatalog(env, new Date("2026-09-23T14:00:01Z"), fetcher, 1, 1),
    /below 90% of the current Space-Track catalog/,
  );
  assert.deepEqual(
    Buffer.from(await (await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).arrayBuffer()),
    Buffer.from(original),
  );

  const partial = makeGpRows(1_200);
  gpRows = Array.from({ length: 14 }, () => partial).flat(); // 16,800 rows, only 1,200 IDs.
  await assert.rejects(
    refreshCatalog(env, new Date("2026-09-23T16:00:02Z"), fetcher, 1),
    /1200 distinct orbits; minimum 15000/,
  );
  const status = await (await handleRequest(new Request("https://catalog.test/v1/status"), env)).json() as {
    sequence: number; lastOutcome: string;
  };
  assert.equal(status.sequence, 1);
  assert.equal(status.lastOutcome, "failed");
  assert.deepEqual(
    Buffer.from(await (await handleRequest(new Request("https://catalog.test/v1/catalog.json.gz"), env)).arrayBuffer()),
    Buffer.from(original),
  );

  gpRows = makeGpRows(19);
  assert.equal(await refreshCatalog(env, new Date("2026-09-23T18:00:03Z"), fetcher, 1, 1), "published");
  const recovered = await (await handleRequest(new Request("https://catalog.test/v1/status"), env)).json() as {
    sequence: number; satelliteCount: number;
  };
  assert.equal(recovered.sequence, 2);
  assert.equal(recovered.satelliteCount, 19);
});

test("first Space-Track GP release can follow CelesTrak or an older source-less pointer", async () => {
  const rows = upstreamRows();
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("celestrak.org")) return Response.json(rows.omm);
    if (url.endsWith("/auth/login") && init?.method !== "POST") {
      return new Response('<input name="spacetrack_csrf_token" value="0123456789abcdef0123456789abcdef">', {
        headers: { "Content-Type": "text/html", "Set-Cookie": "chocolatechip=anonymous; Path=/" },
      });
    }
    if (url.endsWith("/auth/login")) {
      return new Response(null, { status: 302, headers: { Location: "/", "Set-Cookie": "chocolatechip=authenticated; Path=/" } });
    }
    if (url === SPACETRACK_GP_URL) return Response.json([rows.omm[0]]);
    if (url.includes("/satellites/")) return Response.json(rows.satellites);
    if (url.includes("/transmitters/")) return Response.json(rows.transmitters);
    throw new Error("Unexpected upstream request");
  }) as typeof fetch;

  for (const legacyPointer of [false, true]) {
    const pair = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]) as CryptoKeyPair;
    const bucket = new MemoryBucket();
    const env = {
      CATALOG_BUCKET: bucket as unknown as R2Bucket,
      CATALOG_SIGNING_KEY_PKCS8_BASE64: Buffer.from(await webcrypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64"),
      SIGNING_KEY_ID: "test-source-transition",
    } satisfies Env;
    assert.equal(await refreshCatalog(env, new Date("2026-09-23T12:00:00Z"), fetcher, 1), "published");
    const firstPointerObject = await bucket.get("catalog/current.json");
    assert.ok(firstPointerObject);
    const firstPointer = JSON.parse(await firstPointerObject.text()) as { satelliteCount: number; orbitalSource?: string };
    assert.equal(firstPointer.orbitalSource, "celestrak");
    assert.ok(firstPointer.satelliteCount > 1);
    if (legacyPointer) {
      delete firstPointer.orbitalSource;
      await bucket.put("catalog/current.json", JSON.stringify(firstPointer));
    }

    // The last argument lowers the 15,000-orbit floor only for this tiny mock.
    const spaceTrackEnv = {
      ...env,
      SPACETRACK_IDENTITY: "tester@example.org",
      SPACETRACK_PASSWORD: "test-secret",
    } satisfies Env;
    assert.equal(
      await refreshCatalog(spaceTrackEnv, new Date("2026-09-23T14:00:01Z"), fetcher, 1, 1),
      "published",
    );
    const status = await (await handleRequest(new Request("https://catalog.test/v1/status"), spaceTrackEnv)).json() as {
      sequence: number; satelliteCount: number; orbitalSource: string;
    };
    assert.equal(status.sequence, 2);
    assert.equal(status.satelliteCount, 1);
    assert.equal(status.orbitalSource, "space-track");
  }
});

test("overlapping refreshes claim both absent and expired attempt records only once", async () => {
  const pair = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]) as CryptoKeyPair;
  const bucket = new PairedAttemptReadBucket();
  const env = {
    CATALOG_BUCKET: bucket as unknown as R2Bucket,
    CATALOG_SIGNING_KEY_PKCS8_BASE64: Buffer.from(await webcrypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64"),
    SIGNING_KEY_ID: "test-race",
  } satisfies Env;
  const rows = upstreamRows();
  let upstreamCalls = 0;
  const fetcher = (async (input: RequestInfo | URL) => {
    upstreamCalls++;
    const url = String(input);
    if (url.includes("celestrak.org")) return Response.json(rows.omm);
    if (url.includes("/satellites/")) return Response.json(rows.satellites);
    if (url.includes("/transmitters/")) return Response.json(rows.transmitters);
    throw new Error("Unexpected upstream request");
  }) as typeof fetch;

  for (const [round, time] of [
    new Date("2026-09-23T12:00:00Z"),
    new Date("2026-09-23T14:00:01Z"),
  ].entries()) {
    bucket.pauseNextTwoAttemptReads();
    const outcomes = await Promise.all([
      refreshCatalog(env, time, fetcher, 1),
      refreshCatalog(env, time, fetcher, 1),
    ]);
    assert.deepEqual(outcomes.sort(), ["published", "skipped"]);
    assert.equal(upstreamCalls, (round + 1) * (CELESTRAK_OMM_URLS.length + 2));
  }
});
