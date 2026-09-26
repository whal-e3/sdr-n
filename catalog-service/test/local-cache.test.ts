import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash, generateKeyPairSync, verify } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { buildLocalCache, fetchLocalSources, fetchLocalSpaceTrackSources } from "../scripts/local-cache.mjs";
import { SPACETRACK_GP_URL } from "../src/spacetrack.ts";
import { CELESTRAK_OMM_URLS } from "../src/upstream.ts";
import { upstreamRows } from "./fixtures.ts";

function tempRoot(t: { after: (callback: () => void) => void }): string {
  const root = mkdtempSync(join(tmpdir(), "catalog-local-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return root;
}

function fixtureSources(root: string, firstFetchAt = "2026-09-25T10:00:00Z") {
  const rows = upstreamRows();
  const files = [
    ...CELESTRAK_OMM_URLS.map((url, index) => ({
      url, file: `omm-${String(index + 1).padStart(2, "0")}.json`, kind: "omm",
      data: index === 0 ? rows.omm : [],
      fetchedAt: index === 0 ? firstFetchAt : "2026-09-25T11:00:00Z",
    })),
    {
      url: "https://db.satnogs.org/api/satellites/", file: "satnogs-satellites.json", kind: "satellites",
      data: rows.satellites, fetchedAt: "2026-09-25T11:05:00Z",
    },
    {
      url: "https://db.satnogs.org/api/transmitters/", file: "satnogs-transmitters.json", kind: "transmitters",
      data: rows.transmitters, fetchedAt: "2026-09-25T11:06:00Z",
    },
  ];
  const sources = files.map(({ data, ...source }) => {
    const bytes = Buffer.from(JSON.stringify(data));
    writeFileSync(join(root, source.file), bytes);
    return { ...source, rows: data.length, sha256: createHash("sha256").update(bytes).digest("hex") };
  });
  writeFileSync(join(root, "sources.json"), JSON.stringify({ schemaVersion: 1, orbitalSource: "celestrak", sources }));
  return sources;
}

function fixtureKey(root: string) {
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const path = join(root, "private.pkcs8.b64");
  writeFileSync(path, Buffer.from(privateKey.export({ type: "pkcs8", format: "der" })).toString("base64") + "\n");
  return { path, publicKey };
}

function fixtureUserSpaceTrackFiles(root: string) {
  const rows = upstreamRows();
  const rawGp = rows.omm.map((item) => {
    const row = item as Record<string, unknown>;
    return {
      ...row,
      EPOCH: String(row.EPOCH).replace("T", " ").replace(/Z$/, ""),
      MEAN_MOTION: String(row.MEAN_MOTION),
      BSTAR: null,
      TLE_LINE1: "Unused source metadata",
    };
  });
  const paths = {
    spaceTrackGpPath: join(root, "gp.json"),
    satnogsSatellitesPath: join(root, "satnogs-satellites.json"),
    satnogsTransmittersPath: join(root, "satnogs-transmitters.json"),
  };
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(rawGp));
  writeFileSync(paths.satnogsSatellitesPath, JSON.stringify(rows.satellites));
  writeFileSync(paths.satnogsTransmittersPath, JSON.stringify(rows.transmitters));
  return { paths, rawGp };
}

const satnogsDownloadedAt = {
  satnogsSatellitesDownloadedAt: "2026-09-25T09:30:00Z",
  satnogsTransmittersDownloadedAt: "2026-09-25T09:00:00Z",
};

const testCredentials = { identity: "tester@example.org", password: "test-secret" };

function mockSpaceTrackFetch(
  gp: unknown[], onRequest?: (url: string) => void,
  rows: { satellites: unknown[]; transmitters: unknown[] } = upstreamRows(),
): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    onRequest?.(url);
    if (url.endsWith("/auth/login") && init?.method !== "POST") {
      const headers = new Headers({ "Content-Type": "text/html" });
      headers.append("Set-Cookie", "spacetrack_csrf_cookie=first; Path=/; HttpOnly");
      return new Response('<input name="spacetrack_csrf_token" value="0123456789abcdef0123456789abcdef">', { headers });
    }
    if (url.endsWith("/auth/login")) {
      assert.equal(new URLSearchParams(init?.body as URLSearchParams).get("identity"), testCredentials.identity);
      assert.equal(new URLSearchParams(init?.body as URLSearchParams).get("password"), testCredentials.password);
      return new Response(null, { status: 302, headers: { Location: "/" } });
    }
    if (url === SPACETRACK_GP_URL) return Response.json(gp);
    if (url.includes("/satellites/")) {
      assert.equal(new Headers(init?.headers).get("Cookie"), null);
      return Response.json(rows.satellites);
    }
    if (url.includes("/transmitters/")) {
      assert.equal(new Headers(init?.headers).get("Cookie"), null);
      return Response.json(rows.transmitters);
    }
    throw new Error(`Unexpected URL ${url}`);
  }) as typeof fetch;
}

test("local Space-Track fetch saves an atomic hashed source set with recorded completion times", async (t) => {
  const root = tempRoot(t);
  const sourceDir = join(root, "space-track-sources");
  const stateDir = join(root, "state");
  const requests: string[] = [];
  const gp = upstreamRows().omm as Record<string, unknown>[];
  gp[1]!.EPOCH = "2024-01-01T12:00:00Z";
  gp[1]!.DECAY_DATE = "2024-01-02";
  const clock = [
    "2026-09-25T10:00:00Z", "2026-09-25T10:00:05Z",
    "2026-09-25T10:00:10Z", "2026-09-25T10:00:15Z",
  ];
  const manifest = await fetchLocalSpaceTrackSources({
    sourceDir, stateDir, credentials: testCredentials,
    fetcher: mockSpaceTrackFetch(gp, (url) => requests.push(url)),
    now: () => new Date(clock.shift()!), minimumRows: 1, minimumDistinctOrbits: 3,
  });
  assert.deepEqual(requests, [
    "https://db.satnogs.org/api/satellites/", "https://db.satnogs.org/api/transmitters/",
    "https://www.space-track.org/auth/login",
    "https://www.space-track.org/auth/login", SPACETRACK_GP_URL,
  ]);
  assert.deepEqual(readdirSync(sourceDir).sort(), [
    "satnogs-satellites.json", "satnogs-transmitters.json", "sources.json", "space-track-gp.json",
  ]);
  assert.equal(manifest.sources[0]?.distinctOrbits, 3);
  assert.deepEqual(manifest.sources.map((source) => source.fetchedAt), [
    "2026-09-25T10:00:15.000Z", "2026-09-25T10:00:05.000Z", "2026-09-25T10:00:10.000Z",
  ]);
  assert.equal(manifest.sources[0]?.sha256,
    createHash("sha256").update(readFileSync(join(sourceDir, "space-track-gp.json"))).digest("hex"));
  assert.doesNotMatch(readFileSync(join(sourceDir, "sources.json"), "utf8"), /test-secret|tester@example\.org/);
  assert.ok(existsSync(join(stateDir, "space-track-last-attempt.json")));

  const { path } = fixtureKey(root);
  const summary = await buildLocalCache({
    sourceDir, outputDir: join(root, "space-track-release"), privateKeyPath: path,
    keyId: "local-space-track", sequence: 1, expectedMinimumOrbits: 3,
    now: new Date("2026-09-25T10:05:00Z"), minimumRows: 1, minimumDistinctOrbits: 3,
  });
  assert.equal(summary.orbitalSource, "space-track");
  assert.equal(summary.distinctOrbits, 3);
  assert.equal(summary.sourceUpdatedAt, "2026-09-25T10:00:05.000Z");
  assert.match(summary.sourceTimeProvenance, /source-manifest recorded/);
  assert.ok(summary.sourceManifestSha256);
  assert.equal((summary.sourceFiles[0] as { distinctOrbits?: number })?.distinctOrbits, summary.distinctOrbits);
  assert.match(JSON.stringify(summary), /space-track-gp\.json/);
  const signed = JSON.parse(new TextDecoder().decode(gunzipSync(readFileSync(join(root, "space-track-release", "catalog.json.gz")))));
  assert.deepEqual(signed.satellites.map((row: { noradId: string }) => row.noradId),
    gp.map((row) => String(row.NORAD_CAT_ID)).sort((left, right) => Number(left) - Number(right)));
  assert.equal(signed.satellites[1].omm.EPOCH, "2024-01-01T12:00:00.000Z");
  assert.equal(signed.satellites[1].omm.DECAY_DATE, "2024-01-02");

  await assert.rejects(fetchLocalSpaceTrackSources({
    sourceDir: join(root, "too-soon"), stateDir, credentials: testCredentials,
    fetcher: mockSpaceTrackFetch(upstreamRows().omm, (url) => requests.push(url)),
    now: () => new Date("2026-09-25T11:59:59Z"), minimumRows: 1, minimumDistinctOrbits: 3,
  }), /within two hours/);
  assert.equal(requests.length, 5);
});

test("failed or oversized Space-Track GP consumes the rate gate without publishing partial files", async (t) => {
  const root = tempRoot(t);
  const stateDir = join(root, "state");
  let requests = 0;
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requests++;
    const url = String(input);
    if (url === SPACETRACK_GP_URL) {
      return new Response("[]", { headers: {
        "Content-Type": "application/json", "Content-Length": String(128 * 1024 * 1024 + 1),
      } });
    }
    return mockSpaceTrackFetch([], undefined)(input, init);
  }) as typeof fetch;
  const settings = {
    stateDir, credentials: testCredentials, fetcher,
    minimumRows: 1, minimumDistinctOrbits: 3,
  };
  await assert.rejects(fetchLocalSpaceTrackSources({
    ...settings, sourceDir: join(root, "failed"), now: () => new Date("2026-09-25T10:00:00Z"),
  }), /response exceeds size limit/);
  assert.equal(requests, 5);
  assert.equal(existsSync(join(root, "failed")), false);
  assert.deepEqual(readdirSync(root).filter((name) => name.startsWith(".space-track-sources-")), []);
  assert.ok(existsSync(join(stateDir, "space-track-last-attempt.json")));
  await assert.rejects(fetchLocalSpaceTrackSources({
    ...settings, sourceDir: join(root, "retry"), now: () => new Date("2026-09-25T10:01:00Z"),
  }), /within two hours/);
  assert.equal(requests, 5);
});

test("failed SatNOGS metadata fetch cannot publish a partly downloaded Space-Track set", async (t) => {
  const root = tempRoot(t);
  const sourceDir = join(root, "incomplete-source");
  const stateDir = join(root, "state");
  let requests = 0;
  const requestedUrls: string[] = [];
  const normal = mockSpaceTrackFetch(upstreamRows().omm);
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requests++;
    requestedUrls.push(String(input));
    if (String(input).endsWith("/transmitters/")) return new Response("unavailable", { status: 503 });
    return normal(input, init);
  }) as typeof fetch;
  await assert.rejects(fetchLocalSpaceTrackSources({
    sourceDir, stateDir, credentials: testCredentials, fetcher,
    now: () => new Date("2026-09-25T10:00:00Z"),
    minimumRows: 1, minimumDistinctOrbits: 3,
  }), /HTTP 503/);
  assert.equal(requests, 2);
  assert.deepEqual(requestedUrls, [
    "https://db.satnogs.org/api/satellites/", "https://db.satnogs.org/api/transmitters/",
  ]);
  assert.equal(existsSync(sourceDir), false);
  assert.deepEqual(readdirSync(root).filter((name) => name.startsWith(".space-track-sources-")), []);
  assert.ok(existsSync(join(stateDir, "space-track-last-attempt.json")));
});

test("saved Space-Track source rejects missing expected coverage and falsified distinct count", async (t) => {
  const root = tempRoot(t);
  const sourceDir = join(root, "space-track-sources");
  const clock = ["2026-09-25T10:00:00Z", "2026-09-25T10:00:05Z",
    "2026-09-25T10:00:10Z", "2026-09-25T10:00:15Z"];
  await fetchLocalSpaceTrackSources({
    sourceDir, stateDir: join(root, "state"), credentials: testCredentials,
    fetcher: mockSpaceTrackFetch(upstreamRows().omm),
    now: () => new Date(clock.shift()!), minimumRows: 1, minimumDistinctOrbits: 3,
  });
  const { path } = fixtureKey(root);
  const common = { sourceDir, privateKeyPath: path, keyId: "local-test", sequence: 1,
    now: new Date("2026-09-25T10:05:00Z"), minimumRows: 1, minimumDistinctOrbits: 3 };
  await assert.rejects(buildLocalCache({ ...common, outputDir: join(root, "missing-minimum") }),
    /explicit expected minimum orbit count/);
  await assert.rejects(buildLocalCache({ ...common, outputDir: join(root, "low-minimum"),
    expectedMinimumOrbits: 4 }), /minimum 4/);
  const manifestPath = join(sourceDir, "sources.json");
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  manifest.sources[0].distinctOrbits = 4;
  writeFileSync(manifestPath, JSON.stringify(manifest));
  await assert.rejects(buildLocalCache({ ...common, outputDir: join(root, "false-count"),
    expectedMinimumOrbits: 3 }), /distinct-orbit count mismatch/);
});

test("local Space-Track fetch and build enforce the default 15,000-orbit floor", async (t) => {
  const root = tempRoot(t);
  const base = upstreamRows().omm[0] as Record<string, unknown>;
  const gp = Array.from({ length: 15_000 }, (_, index) => ({
    ...base, NORAD_CAT_ID: 500_000 + index, OBJECT_NAME: `GP OBJECT ${index}`,
  }));
  const metadata = {
    satellites: Array.from({ length: 1_000 }, (_, index) => ({
      norad_cat_id: 500_000 + index, name: `GP OBJECT ${index}`,
    })),
    transmitters: Array.from({ length: 1_000 }, (_, index) => ({
      uuid: `transmitter${500_000 + index}`, norad_cat_id: 500_000 + index,
      downlink_low: 145_800_000, mode: "NFM", status: "inactive",
    })),
  };
  const sourceDir = join(root, "large-source");
  const settings = {
    credentials: testCredentials, fetcher: mockSpaceTrackFetch(gp, undefined, metadata),
    now: () => new Date(),
  };
  const manifest = await fetchLocalSpaceTrackSources({
    ...settings, sourceDir, stateDir: join(root, "successful-state"),
  });
  assert.equal(manifest.sources[0]?.distinctOrbits, 15_000);
  const { path } = fixtureKey(root);
  const outputDir = join(root, "large-release");
  const command = spawnSync(process.execPath, [
    "--disable-warning=ExperimentalWarning", "--experimental-strip-types",
    fileURLToPath(new URL("../scripts/local-cache.mjs", import.meta.url)), "build",
    "--source-dir", sourceDir, "--expected-minimum-orbits", "15000",
    "--output-dir", outputDir, "--private-key", path,
    "--key-id", "local-space-track", "--sequence", "1",
  ], { encoding: "utf8" });
  assert.equal(command.status, 0, command.stderr);
  const summary = JSON.parse(readFileSync(join(outputDir, "summary.json"), "utf8"));
  assert.equal(summary.distinctOrbits, 15_000);
  assert.equal(summary.sourceFiles[0]?.rows, 15_000);

  await assert.rejects(fetchLocalSpaceTrackSources({
    ...settings, sourceDir: join(root, "partial-source"),
    stateDir: join(root, "partial-state"),
    fetcher: mockSpaceTrackFetch(gp.slice(0, 14_999), undefined, metadata),
  }), /14999 distinct valid orbits; minimum 15000/);
  assert.equal(existsSync(join(root, "partial-source")), false);
});

test("fetch saves complete hashed source set and blocks a second attempt for two hours", async (t) => {
  const root = tempRoot(t);
  const stateDir = join(root, "state");
  let requests = 0;
  const rows = upstreamRows();
  const fetcher = async (url: string) => {
    requests++;
    const data = url.includes("/satellites/") ? rows.satellites
      : url.includes("/transmitters/") ? rows.transmitters : rows.omm;
    return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  const first = await fetchLocalSources({
    sourceDir: join(root, "fetch-1"), stateDir, fetcher, now: () => new Date("2026-09-25T10:00:00Z"),
  });
  assert.equal(first.sources.length, CELESTRAK_OMM_URLS.length + 2);
  assert.equal(requests, CELESTRAK_OMM_URLS.length + 2);
  assert.equal(first.sources[0].sha256, createHash("sha256").update(readFileSync(join(root, "fetch-1", "omm-01.json"))).digest("hex"));
  await assert.rejects(
    fetchLocalSources({ sourceDir: join(root, "fetch-2"), stateDir, fetcher, now: () => new Date("2026-09-25T11:59:59Z") }),
    /within two hours/,
  );
  assert.equal(requests, CELESTRAK_OMM_URLS.length + 2);
  await fetchLocalSources({
    sourceDir: join(root, "fetch-3"), stateDir, fetcher, now: () => new Date("2026-09-25T12:00:00Z"),
  });
  assert.equal(requests, 2 * (CELESTRAK_OMM_URLS.length + 2));
});

test("a failed source fetch still consumes the two-hour attempt gate", async (t) => {
  const root = tempRoot(t);
  let requests = 0;
  const fetcher = async () => { requests++; return new Response("error", { status: 503 }); };
  await assert.rejects(
    fetchLocalSources({ sourceDir: join(root, "failed"), stateDir: join(root, "state"), fetcher,
      now: () => new Date("2026-09-25T10:00:00Z") }),
    /HTTP 503/,
  );
  await assert.rejects(
    fetchLocalSources({ sourceDir: join(root, "retry"), stateDir: join(root, "state"), fetcher,
      now: () => new Date("2026-09-25T10:01:00Z") }),
    /within two hours/,
  );
  assert.equal(requests, 1);
});

test("build emits a phone cache with verified gzip signature and oldest orbit fetch time", async (t) => {
  const root = tempRoot(t);
  const sourceDir = join(root, "sources");
  const outputDir = join(root, "release-1");
  mkdirSync(sourceDir);
  fixtureSources(sourceDir);
  const { path, publicKey } = fixtureKey(root);
  const summary = await buildLocalCache({
    sourceDir, outputDir, privateKeyPath: path, keyId: "local-test", sequence: 7,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 3,
  });
  assert.equal(summary.distinctOrbits, 3);
  assert.equal(summary.sourceUpdatedAt, "2026-09-25T10:00:00.000Z");
  assert.equal(summary.sourceExpiresAt, "2026-09-28T10:00:00.000Z");
  assert.equal(summary.catalogSourceFreshAtBuild, true);
  assert.equal(summary.sourceFiles.length, CELESTRAK_OMM_URLS.length + 2);
  const cache = readFileSync(join(outputDir, "catalog.cache"));
  const header = new DataView(cache.buffer, cache.byteOffset, 8);
  assert.equal(header.getUint32(0), 0x53415431);
  const signatureLength = header.getUint32(4);
  const envelope = JSON.parse(new TextDecoder().decode(cache.subarray(8, 8 + signatureLength)));
  const compressed = cache.subarray(8 + signatureLength);
  assert.deepEqual(compressed, readFileSync(join(outputDir, "catalog.json.gz")));
  assert.equal(envelope.sha256, createHash("sha256").update(compressed).digest("hex"));
  assert.equal(verify(null, compressed, publicKey, Buffer.from(envelope.signature, "base64url")), true);
  assert.equal(readFileSync(join(outputDir, "catalog-public.raw.b64"), "utf8").trim(),
    Buffer.from(publicKey.export({ format: "jwk" }).x!, "base64url").toString("base64"));
  const manifest = JSON.parse(new TextDecoder().decode(gunzipSync(compressed)));
  assert.equal(manifest.sequence, 7);
  assert.equal(manifest.sourceUpdatedAt, summary.sourceUpdatedAt);
  assert.equal(manifest.generatedAt, "2026-09-25T12:00:00.000Z");
});

test("offline user Space-Track GP file builds a signed cache with declared provenance and source time", async (t) => {
  const root = tempRoot(t);
  const { paths, rawGp } = fixtureUserSpaceTrackFiles(root);
  rawGp[1]!.EPOCH = "2024-01-01 12:00:00";
  (rawGp[1] as Record<string, unknown>).DECAY_DATE = "2024-01-02";
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(rawGp));
  const { path, publicKey } = fixtureKey(root);
  const outputDir = join(root, "space-track-release");
  const summary = await buildLocalCache({
    ...paths, ...satnogsDownloadedAt, gpDownloadedAt: "2026-09-25T10:00:00Z", outputDir,
    privateKeyPath: path, keyId: "local-space-track", sequence: 8,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 3,
    expectedMinimumOrbits: 3,
  });
  assert.equal(summary.orbitalSource, "space-track");
  assert.equal(summary.distinctOrbits, 3);
  assert.equal(summary.rawOrbitRows, rawGp.length);
  assert.equal(summary.sourceUpdatedAt, "2026-09-25T09:00:00.000Z");
  assert.equal(summary.sourceExpiresAt, "2026-09-28T09:00:00.000Z");
  assert.equal(summary.catalogSourceFreshAtBuild, true);
  assert.match(summary.sourceTimeProvenance, /operator-declared/);
  assert.equal(summary.sourceManifestSha256, null);
  assert.equal(summary.sourceFiles.length, 3);
  assert.equal(summary.sourceFiles[0]?.rows, 3);
  assert.equal(summary.sourceFiles[0]?.sha256,
    createHash("sha256").update(readFileSync(paths.spaceTrackGpPath)).digest("hex"));
  assert.equal((summary.sourceFiles[1] as { downloadedAt?: string }).downloadedAt,
    satnogsDownloadedAt.satnogsSatellitesDownloadedAt);
  assert.equal((summary.sourceFiles[2] as { downloadedAt?: string }).downloadedAt,
    satnogsDownloadedAt.satnogsTransmittersDownloadedAt);
  assert.equal(summary.expectedMinimumOrbits, 3);
  assert.equal(summary.requiredOrbits, 3);

  const cache = readFileSync(join(outputDir, "catalog.cache"));
  const header = new DataView(cache.buffer, cache.byteOffset, 8);
  const signatureLength = header.getUint32(4);
  assert.equal(header.getUint32(0), 0x53415431);
  const envelope = JSON.parse(new TextDecoder().decode(cache.subarray(8, 8 + signatureLength)));
  const compressed = cache.subarray(8 + signatureLength);
  assert.equal(verify(null, compressed, publicKey, Buffer.from(envelope.signature, "base64url")), true);
  const manifest = JSON.parse(new TextDecoder().decode(gunzipSync(compressed)));
  assert.equal(manifest.sourceUpdatedAt, summary.sourceUpdatedAt);
  assert.match(manifest.attribution.join(" "), /user-supplied Space-Track GP JSON/i);
  assert.equal(manifest.satellites.length, 3);
  assert.equal(manifest.satellites[0].omm.TLE_LINE1, undefined);
  assert.equal(manifest.satellites[1].omm.EPOCH, "2024-01-01T12:00:00.000Z");
  assert.equal(manifest.satellites[1].omm.DECAY_DATE, "2024-01-02");
});

test("Space-Track offline build command accepts the documented file arguments", (t) => {
  const root = tempRoot(t);
  const { paths, rawGp } = fixtureUserSpaceTrackFiles(root);
  const { path } = fixtureKey(root);
  const outputDir = join(root, "cli-release");
  const downloadedAt = new Date(Date.now() - 60 * 60 * 1_000).toISOString();
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(Array.from({ length: 15_000 }, (_, index) => ({
    ...rawGp[0], NORAD_CAT_ID: 600_000 + index, OBJECT_NAME: `GP OBJECT ${index}`,
  }))));
  writeFileSync(paths.satnogsSatellitesPath, JSON.stringify(Array.from({ length: 1_000 }, (_, index) => ({
    norad_cat_id: 600_000 + index, name: `GP OBJECT ${index}`,
  }))));
  writeFileSync(paths.satnogsTransmittersPath, JSON.stringify(Array.from({ length: 1_000 }, (_, index) => ({
    uuid: `transmitter${600_000 + index}`, norad_cat_id: 600_000 + index,
    downlink_low: 145_800_000, mode: "NFM", status: "inactive",
  }))));
  const command = spawnSync(process.execPath, [
    "--disable-warning=ExperimentalWarning", "--experimental-strip-types",
    fileURLToPath(new URL("../scripts/local-cache.mjs", import.meta.url)), "build",
    "--space-track-gp", paths.spaceTrackGpPath,
    "--satnogs-satellites", paths.satnogsSatellitesPath,
    "--satnogs-transmitters", paths.satnogsTransmittersPath,
    "--gp-downloaded-at", downloadedAt,
    "--satnogs-satellites-downloaded-at", downloadedAt,
    "--satnogs-transmitters-downloaded-at", downloadedAt,
    "--expected-minimum-orbits", "15000",
    "--output-dir", outputDir, "--private-key", path,
    "--key-id", "local-space-track", "--sequence", "2",
  ], { encoding: "utf8" });
  assert.equal(command.status, 0, command.stderr);
  assert.equal(JSON.parse(command.stdout).distinctOrbits, 15_000);
  assert.equal(JSON.parse(readFileSync(join(outputDir, "summary.json"), "utf8")).orbitalSource, "space-track");
});

test("user Space-Track GP build rejects stale, future, partial, and invalid source claims", async (t) => {
  const root = tempRoot(t);
  const { paths } = fixtureUserSpaceTrackFiles(root);
  const { path } = fixtureKey(root);
  const common = {
    ...paths, ...satnogsDownloadedAt, privateKeyPath: path, keyId: "local-space-track", sequence: 1,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 3,
    expectedMinimumOrbits: 3,
  };
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "missing-metadata-time"),
    satnogsTransmittersDownloadedAt: undefined } as unknown as Parameters<typeof buildLocalCache>[0]),
  /both SatNOGS files, their UTC download times/);
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "missing-expected-minimum"),
    expectedMinimumOrbits: undefined } as unknown as Parameters<typeof buildLocalCache>[0]),
  /explicit expected minimum orbit count/);
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-20T00:00:00Z",
    outputDir: join(root, "stale") }), /72-hour reception freshness limit/);
  const stale = await buildLocalCache({ ...common, gpDownloadedAt: "2026-09-20T00:00:00Z",
    outputDir: join(root, "stale-tracking"), allowStale: true });
  assert.equal(stale.catalogSourceFreshAtBuild, false);
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T13:00:00Z",
    outputDir: join(root, "future") }), /in the future/);
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "partial"), expectedMinimumOrbits: 4 }), /minimum 4/);
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "stale-metadata"),
    satnogsTransmittersDownloadedAt: "2026-09-20T00:00:00Z" }), /72-hour reception freshness limit/);
  const trackingOnly = await buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "stale-metadata-tracking"), allowStale: true,
    satnogsTransmittersDownloadedAt: "2026-09-20T00:00:00Z" });
  assert.equal(trackingOnly.sourceUpdatedAt, "2026-09-20T00:00:00.000Z");
  assert.equal(trackingOnly.catalogSourceFreshAtBuild, false);
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "future-metadata"),
    satnogsSatellitesDownloadedAt: "2026-09-25T13:00:00Z" }), /SatNOGS satellites download time is in the future/);
  const corrupt = JSON.parse(readFileSync(paths.spaceTrackGpPath, "utf8"));
  corrupt[1].REF_FRAME = "ITRF";
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(corrupt));
  await assert.rejects(buildLocalCache({ ...common, gpDownloadedAt: "2026-09-25T10:00:00Z",
    outputDir: join(root, "invalid") }), /unsupported orbital coordinates or theory/);
});

test("user Space-Track GP build accepts over 20 MB and rejects over the 40 MB Android limit", async (t) => {
  const root = tempRoot(t);
  const { paths } = fixtureUserSpaceTrackFiles(root);
  const { path } = fixtureKey(root);
  const gp = JSON.parse(readFileSync(paths.spaceTrackGpPath, "utf8"));
  const options = {
    ...paths, ...satnogsDownloadedAt,
    gpDownloadedAt: "2026-09-25T10:00:00Z",
    privateKeyPath: path, keyId: "local-space-track", sequence: 1,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 3,
    expectedMinimumOrbits: 3,
  };
  // The builder stores OBJECT_NAME in both the display name and OMM data.
  gp[0].OBJECT_NAME = "A".repeat(11_000_000);
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(gp));
  const accepted = await buildLocalCache({ ...options, outputDir: join(root, "within-limit") });
  assert.ok(accepted.plainBytes > 20_000_000 && accepted.plainBytes < 40_000_000);

  gp[0].OBJECT_NAME = "A".repeat(20_000_000);
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(gp));
  await assert.rejects(buildLocalCache({
    ...options, outputDir: join(root, "oversized"),
  }), /Android uncompressed size limit/);
});

test("user Space-Track GP build accepts a synthetic 15,000-orbit source at the default coverage floor", async (t) => {
  const root = tempRoot(t);
  const { paths, rawGp } = fixtureUserSpaceTrackFiles(root);
  const { path } = fixtureKey(root);
  const gp = Array.from({ length: 15_000 }, (_, index) => ({
    ...rawGp[0], NORAD_CAT_ID: 500_000 + index, OBJECT_NAME: `GP OBJECT ${index}`,
  }));
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(gp));
  const summary = await buildLocalCache({
    ...paths, ...satnogsDownloadedAt,
    gpDownloadedAt: "2026-09-25T10:00:00Z", outputDir: join(root, "coverage-floor"),
    privateKeyPath: path, keyId: "local-space-track", sequence: 1,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1,
    expectedMinimumOrbits: 15_000,
  });
  assert.equal(summary.rawOrbitRows, 15_000);
  assert.equal(summary.distinctOrbits, 15_000);
  assert.ok(summary.plainBytes < 40_000_000);
  assert.ok(summary.gzipBytes < 6_000_000);
  await assert.rejects(buildLocalCache({
    ...paths, ...satnogsDownloadedAt,
    gpDownloadedAt: "2026-09-25T10:00:00Z", outputDir: join(root, "expected-broad-coverage"),
    privateKeyPath: path, keyId: "local-space-track", sequence: 2,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1,
    expectedMinimumOrbits: 30_000,
  }), /Only 15000 distinct orbits; minimum 30000/);
});

test("offline Space-Track refresh enforces 90% of a verified previous cache", async (t) => {
  const root = tempRoot(t);
  const { paths } = fixtureUserSpaceTrackFiles(root);
  const { path } = fixtureKey(root);
  const common = {
    ...paths, ...satnogsDownloadedAt, gpDownloadedAt: "2026-09-25T10:00:00Z",
    privateKeyPath: path, keyId: "local-space-track",
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 1,
    expectedMinimumOrbits: 2,
  };
  const initial = join(root, "initial-release");
  await buildLocalCache({ ...common, outputDir: initial, sequence: 1 });
  const previousCachePath = join(initial, "catalog.cache");
  const smallerGp = JSON.parse(readFileSync(paths.spaceTrackGpPath, "utf8")) as unknown[];
  writeFileSync(paths.spaceTrackGpPath, JSON.stringify(smallerGp.slice(0, 2)));
  await assert.rejects(buildLocalCache({ ...common, outputDir: join(root, "partial-refresh"),
    sequence: 2, previousCachePath }), /Only 2 distinct orbits; minimum 3/);
  const corrupted = join(root, "corrupted.cache");
  const bytes = readFileSync(previousCachePath);
  bytes[bytes.length - 1] ^= 1;
  writeFileSync(corrupted, bytes);
  await assert.rejects(buildLocalCache({ ...common, outputDir: join(root, "corrupt-refresh"),
    sequence: 2, previousCachePath: corrupted }), /invalid signature or sequence/);
  await assert.rejects(buildLocalCache({ ...common, outputDir: join(root, "rollback-refresh"),
    sequence: 1, previousCachePath }), /invalid signature or sequence/);
});

test("build rejects missing, changed, and future-dated saved responses", async (t) => {
  const root = tempRoot(t);
  const sourceDir = join(root, "sources");
  mkdirSync(sourceDir);
  const sources = fixtureSources(sourceDir);
  const { path } = fixtureKey(root);
  const settings = { sourceDir, privateKeyPath: path, keyId: "local-test", sequence: 1,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 3 };
  const save = () => writeFileSync(join(sourceDir, "sources.json"), JSON.stringify({
    schemaVersion: 1, orbitalSource: "celestrak", sources,
  }));

  sources.pop(); save();
  await assert.rejects(buildLocalCache({ ...settings, outputDir: join(root, "incomplete") }), /incomplete/);
  fixtureSources(sourceDir);
  writeFileSync(join(sourceDir, "omm-01.json"), "[]");
  await assert.rejects(buildLocalCache({ ...settings, outputDir: join(root, "changed") }), /hash or size mismatch/);
  const corrected = fixtureSources(sourceDir);
  corrected[0]!.fetchedAt = "2026-09-26T10:00:00Z";
  writeFileSync(join(sourceDir, "sources.json"), JSON.stringify({
    schemaVersion: 1, orbitalSource: "celestrak", sources: corrected,
  }));
  await assert.rejects(buildLocalCache({ ...settings, outputDir: join(root, "future") }), /Future fetch time/);
});

test("stale orbital responses fail by default and require an explicit tracking-only override", async (t) => {
  const root = tempRoot(t);
  const sourceDir = join(root, "sources");
  mkdirSync(sourceDir);
  fixtureSources(sourceDir, "2026-09-20T10:00:00Z");
  const { path } = fixtureKey(root);
  const settings = { sourceDir, privateKeyPath: path, keyId: "local-test", sequence: 1,
    now: new Date("2026-09-25T12:00:00Z"), minimumRows: 1, minimumDistinctOrbits: 3 };
  await assert.rejects(buildLocalCache({ ...settings, outputDir: join(root, "fresh-required") }),
    /72-hour reception freshness limit/);
  const summary = await buildLocalCache({ ...settings, outputDir: join(root, "tracking-only"), allowStale: true });
  assert.equal(summary.catalogSourceFreshAtBuild, false);
  assert.equal(summary.sourceUpdatedAt, "2026-09-20T10:00:00.000Z");
});

test("build refuses a private key path inside the workspace", async (t) => {
  const root = tempRoot(t);
  await assert.rejects(buildLocalCache({
    sourceDir: root, outputDir: join(root, "output"),
    privateKeyPath: fileURLToPath(new URL("../fixtures/demo-catalog.json", import.meta.url)),
    keyId: "local-test", sequence: 1,
  }), /outside the workspace/);
});

test("fetch refuses a source directory inside the workspace before any request", async () => {
  let requests = 0;
  await assert.rejects(fetchLocalSources({
    sourceDir: fileURLToPath(new URL("../fixtures/forbidden-output", import.meta.url)),
    fetcher: async () => { requests++; return new Response("[]"); },
  }), /outside the workspace/);
  assert.equal(requests, 0);
});
