import { createHash, createPrivateKey, createPublicKey, verify } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  unlinkSync,
  writeFileSync,
  closeSync,
} from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { gunzipSync } from "node:zlib";
import { buildCatalog } from "../src/catalog.ts";
import { gzipJson, signCatalog } from "../src/crypto.ts";
import { normalizeSpaceTrackOmm, openSpaceTrackGpResponse, SPACETRACK_GP_URL } from "../src/spacetrack.ts";
import { CELESTRAK_OMM_URLS } from "../src/upstream.ts";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const WORKSPACE_ROOT = resolve(dirname(SCRIPT_PATH), "../..");
const SATELLITES_URL = "https://db.satnogs.org/api/satellites/";
const TRANSMITTERS_URL = "https://db.satnogs.org/api/transmitters/";
const FETCH_INTERVAL_MS = 2 * 60 * 60 * 1000;
const MAX_SOURCE_BYTES = 30_000_000;
// The real unfiltered GP export contains duplicate TLE text and metadata:
// September 26's 69,438-row response was 78,864,118 bytes before compaction.
// This desktop-source bound is separate from the unchanged Android limits.
const MAX_USER_GP_BYTES = 128 * 1024 * 1024;
const MAX_PLAIN_BYTES = 40_000_000;
const MAX_GZIP_BYTES = 6_000_000;
const MAX_SIGNATURE_BYTES = 4_096;
const FRESHNESS_MS = 72 * 60 * 60 * 1000;

function insideWorkspace(path) {
  const relation = relative(WORKSPACE_ROOT, path);
  return relation === "" || (relation !== ".." && !relation.startsWith(`..${sep}`) && !isAbsolute(relation));
}

function requireExternalFile(path, label) {
  const actual = realpathSync(path);
  if (insideWorkspace(actual) || !statSync(actual).isFile()) {
    throw new Error(`${label} must be a regular file outside the workspace`);
  }
  return actual;
}

function requireNewExternalDirectory(path, label) {
  const output = resolve(path);
  const parent = dirname(output);
  mkdirSync(parent, { recursive: true, mode: 0o700 });
  if (insideWorkspace(realpathSync(parent)) || existsSync(output)) {
    throw new Error(`${label} directory must be a new directory outside the workspace`);
  }
  return output;
}

function utcTime(value, label) {
  if (typeof value !== "string" || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(value)) {
    throw new Error(`${label} must be an ISO UTC timestamp`);
  }
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) throw new Error(`${label} is invalid`);
  return timestamp;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function safeFileName(file) {
  if (typeof file !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]*\.json$/.test(file) || basename(file) !== file) {
    throw new Error("Source file must be a JSON basename within its source directory");
  }
  return file;
}

async function responseBytes(response, url, maxBytes = MAX_SOURCE_BYTES, requireJsonContentType = false, now = () => new Date()) {
  if (response.status !== 200) throw new Error(`Upstream ${new URL(url).host} returned HTTP ${response.status}`);
  if (requireJsonContentType && !response.headers.get("Content-Type")?.toLowerCase().includes("json")) {
    throw new Error(`Upstream ${new URL(url).host} did not return JSON`);
  }
  const contentLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    throw new Error(`Upstream ${new URL(url).host} response exceeds size limit`);
  }
  if (!response.body) throw new Error(`Upstream ${new URL(url).host} has no body`);
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.byteLength;
    if (size > maxBytes) throw new Error(`Upstream ${new URL(url).host} response exceeds size limit`);
    chunks.push(Buffer.from(chunk));
  }
  // Record when the complete HTTP body arrived, before local JSON parsing or validation.
  const completion = now();
  if (!(completion instanceof Date) || !Number.isFinite(completion.valueOf())) {
    throw new Error("Invalid response completion time");
  }
  const fetchedAt = completion.toISOString();
  const bytes = Buffer.concat(chunks);
  let parsed;
  try {
    parsed = JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new Error(`Upstream ${new URL(url).host} did not return valid JSON`);
  }
  if (!Array.isArray(parsed)) throw new Error(`Upstream ${new URL(url).host} did not return a JSON array`);
  return { bytes, rows: parsed.length, data: parsed, fetchedAt };
}

function claimFetchAttempt(stateDir, now, source = "celestrak") {
  if (!(now instanceof Date) || !Number.isFinite(now.valueOf())) throw new Error("Invalid fetch time");
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const lockPath = join(stateDir, `${source}-fetch.lock`);
  const lock = openSync(lockPath, "wx", 0o600);
  try {
    writeFileSync(lock, JSON.stringify({ pid: process.pid, startedAt: now.toISOString() }) + "\n");
    const statePath = join(stateDir, `${source}-last-attempt.json`);
    if (existsSync(statePath)) {
      const previous = JSON.parse(readFileSync(statePath, "utf8"));
      const elapsed = now.valueOf() - utcTime(previous.attemptedAt, "Previous fetch time");
      if (elapsed < FETCH_INTERVAL_MS) {
        throw new Error(`${source === "space-track" ? "Space-Track GP" : "CelesTrak"} fetch already attempted within two hours; reuse saved responses`);
      }
    }
    const temporary = join(stateDir, `${source}-last-attempt.${process.pid}.tmp`);
    try {
      writeFileSync(temporary, JSON.stringify({ attemptedAt: now.toISOString() }) + "\n", { flag: "wx", mode: 0o600 });
      renameSync(temporary, statePath);
    } finally {
      rmSync(temporary, { force: true });
    }
    return { lock, lockPath };
  } catch (error) {
    closeSync(lock);
    unlinkSync(lockPath);
    throw error;
  }
}

/** Fetches the configured source set once. The durable gate is written before any HTTP request. */
export async function fetchLocalSources({
  sourceDir,
  stateDir = join(homedir(), ".cache", "satellite-eavesdropper"),
  fetcher = fetch,
  now = () => new Date(),
  orbitUrls = CELESTRAK_OMM_URLS,
}) {
  const directory = requireNewExternalDirectory(sourceDir, "Source");
  const claim = claimFetchAttempt(resolve(stateDir), now());
  try {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    const sources = [];
    const urls = [
      ...orbitUrls.map((url, index) => ({ url, file: `omm-${String(index + 1).padStart(2, "0")}.json`, kind: "omm" })),
      { url: SATELLITES_URL, file: "satnogs-satellites.json", kind: "satellites" },
      { url: TRANSMITTERS_URL, file: "satnogs-transmitters.json", kind: "transmitters" },
    ];
    for (const source of urls) {
      const response = await fetcher(source.url, {
        headers: { Accept: "application/json" },
        redirect: "manual",
        signal: AbortSignal.timeout(60_000),
      });
      const { bytes, rows, fetchedAt } = await responseBytes(response, source.url, MAX_SOURCE_BYTES, false, now);
      writeFileSync(join(directory, source.file), bytes, { flag: "wx", mode: 0o600 });
      sources.push({ ...source, fetchedAt, sha256: sha256(bytes), rows });
    }
    const sourceManifest = { schemaVersion: 1, orbitalSource: "celestrak", sources };
    writeFileSync(join(directory, "sources.json"), JSON.stringify(sourceManifest, null, 2) + "\n", {
      flag: "wx", mode: 0o600,
    });
    return sourceManifest;
  } finally {
    closeSync(claim.lock);
    unlinkSync(claim.lockPath);
  }
}

/** Fetches one authenticated GP response and both SatNOGS arrays into an atomic local source set. */
export async function fetchLocalSpaceTrackSources({
  sourceDir,
  credentials,
  stateDir = join(homedir(), ".cache", "satellite-eavesdropper"),
  fetcher = fetch,
  now = () => new Date(),
  minimumRows = 1_000,
  minimumDistinctOrbits = 15_000,
}) {
  if (!credentials || typeof credentials.identity !== "string" ||
      typeof credentials.password !== "string" ||
      !credentials.identity.trim() || !credentials.password) {
    throw new Error("Set SPACETRACK_IDENTITY and SPACETRACK_PASSWORD in the process environment");
  }
  const directory = requireNewExternalDirectory(sourceDir, "Source");
  const claim = claimFetchAttempt(resolve(stateDir), now(), "space-track");
  let staging;
  try {
    staging = mkdtempSync(join(dirname(directory), ".space-track-sources-"));
    // Check public metadata first so a SatNOGS failure does not waste the GP request.
    const metadataSources = [];
    for (const source of [
      { url: SATELLITES_URL, file: "satnogs-satellites.json", kind: "satellites" },
      { url: TRANSMITTERS_URL, file: "satnogs-transmitters.json", kind: "transmitters" },
    ]) {
      const response = await fetcher(source.url, {
        headers: { Accept: "application/json" }, redirect: "manual",
        signal: AbortSignal.timeout(60_000),
      });
      const result = await responseBytes(response, source.url, MAX_SOURCE_BYTES, true, now);
      if (result.rows < minimumRows) throw new Error(`Upstream ${new URL(source.url).host} dataset is unexpectedly small`);
      writeFileSync(join(staging, source.file), result.bytes, { flag: "wx", mode: 0o600 });
      metadataSources.push({ ...source, fetchedAt: result.fetchedAt, sha256: sha256(result.bytes), rows: result.rows });
    }
    const gpResponse = await openSpaceTrackGpResponse(credentials, fetcher);
    const gp = await responseBytes(gpResponse, SPACETRACK_GP_URL, MAX_USER_GP_BYTES, true, now);
    if (gp.rows < minimumRows) throw new Error("Space-Track GP dataset is unexpectedly small");
    const distinctOrbits = new Set(gp.data.map((row) =>
      String(Number(normalizeSpaceTrackOmm(row).NORAD_CAT_ID)))).size;
    if (distinctOrbits < minimumDistinctOrbits) {
      throw new Error(`Space-Track GP has ${distinctOrbits} distinct valid orbits; minimum ${minimumDistinctOrbits}`);
    }
    writeFileSync(join(staging, "space-track-gp.json"), gp.bytes, { flag: "wx", mode: 0o600 });
    const sources = [{
      url: SPACETRACK_GP_URL, file: "space-track-gp.json", kind: "omm",
      fetchedAt: gp.fetchedAt, sha256: sha256(gp.bytes), rows: gp.rows,
      distinctOrbits,
    }, ...metadataSources];
    const sourceManifest = {
      schemaVersion: 1, orbitalSource: "space-track", provenance: "recorded local HTTP response completion times",
      sources,
    };
    writeFileSync(join(staging, "sources.json"), JSON.stringify(sourceManifest, null, 2) + "\n", {
      flag: "wx", mode: 0o600,
    });
    renameSync(staging, directory);
    return sourceManifest;
  } catch (error) {
    if (staging) rmSync(staging, { recursive: true, force: true });
    throw error;
  } finally {
    closeSync(claim.lock);
    unlinkSync(claim.lockPath);
  }
}

function readSavedSources(sourceDir, generatedAt, orbitUrls) {
  const manifestBytes = readFileSync(join(sourceDir, "sources.json"));
  const sources = JSON.parse(manifestBytes.toString("utf8"));
  if (sources.schemaVersion !== 1 ||
      !["celestrak", "space-track"].includes(sources.orbitalSource) ||
      !Array.isArray(sources.sources)) {
    throw new Error("Unsupported saved source manifest");
  }
  const expected = sources.orbitalSource === "space-track"
    ? [SPACETRACK_GP_URL, SATELLITES_URL, TRANSMITTERS_URL]
    : [...orbitUrls, SATELLITES_URL, TRANSMITTERS_URL];
  if (sources.sources.length !== expected.length) throw new Error("Saved source set is incomplete");
  const byUrl = new Map();
  const filesSeen = new Set();
  for (const source of sources.sources) {
    if (!expected.includes(source.url) || byUrl.has(source.url)) throw new Error("Unexpected or duplicate source URL");
    const expectedKind = source.url === SATELLITES_URL ? "satellites"
      : source.url === TRANSMITTERS_URL ? "transmitters" : "omm";
    if (source.kind !== expectedKind) throw new Error("Saved source kind does not match its URL");
    const file = safeFileName(source.file);
    if (filesSeen.has(file)) throw new Error(`Duplicate saved source file: ${file}`);
    filesSeen.add(file);
    const bytes = readFileSync(join(sourceDir, file));
    const maxBytes = source.url === SPACETRACK_GP_URL ? MAX_USER_GP_BYTES : MAX_SOURCE_BYTES;
    if (bytes.byteLength > maxBytes || sha256(bytes) !== source.sha256) {
      throw new Error(`Source hash or size mismatch: ${file}`);
    }
    const rows = JSON.parse(bytes.toString("utf8"));
    if (!Array.isArray(rows) || rows.length !== source.rows) throw new Error(`Source row count mismatch: ${file}`);
    const fetchedAt = utcTime(source.fetchedAt, `Fetch time for ${file}`);
    if (fetchedAt > generatedAt.valueOf()) throw new Error(`Future fetch time: ${file}`);
    if (source.url === SPACETRACK_GP_URL &&
        (!Number.isSafeInteger(source.distinctOrbits) || source.distinctOrbits < 1)) {
      throw new Error("Saved Space-Track source is missing its distinct-orbit count");
    }
    byUrl.set(source.url, { rows, source });
  }
  const ordered = expected.map((url) => byUrl.get(url));
  if (ordered.some((item) => !item)) throw new Error("Saved source set is incomplete");
  return { ordered, manifestSha256: sha256(manifestBytes), orbitalSource: sources.orbitalSource };
}

function readUserJsonArray(path, label, maxBytes) {
  const file = requireExternalFile(path, label);
  const size = statSync(file).size;
  if (size < 2 || size > maxBytes) throw new Error(`${label} is empty or exceeds its size limit`);
  const bytes = readFileSync(file);
  let rows;
  try {
    rows = JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new Error(`${label} is not valid JSON`);
  }
  if (!Array.isArray(rows)) throw new Error(`${label} must be a JSON array`);
  return {
    rows,
    provenance: "operator-supplied file; origin not independently verified",
    file: basename(file),
    sha256: sha256(bytes),
    bytes: size,
  };
}

function readUserSpaceTrackSources({
  spaceTrackGpPath, satnogsSatellitesPath, satnogsTransmittersPath,
  gpDownloadedAt, satnogsSatellitesDownloadedAt, satnogsTransmittersDownloadedAt,
  generatedAt, minimumRows,
}) {
  const downloadTimes = [
    ["Space-Track GP", gpDownloadedAt],
    ["SatNOGS satellites", satnogsSatellitesDownloadedAt],
    ["SatNOGS transmitters", satnogsTransmittersDownloadedAt],
  ].map(([label, value]) => {
    const timestamp = utcTime(value, `${label} download time`);
    if (timestamp > generatedAt.valueOf()) throw new Error(`${label} download time is in the future`);
    return timestamp;
  });
  const sourceTime = Math.min(...downloadTimes);
  const gp = readUserJsonArray(spaceTrackGpPath, "Space-Track GP file", MAX_USER_GP_BYTES);
  const satellites = readUserJsonArray(satnogsSatellitesPath, "SatNOGS satellites file", MAX_SOURCE_BYTES);
  const transmitters = readUserJsonArray(satnogsTransmittersPath, "SatNOGS transmitters file", MAX_SOURCE_BYTES);
  if (gp.rows.length < minimumRows || satellites.rows.length < minimumRows ||
      transmitters.rows.length < minimumRows) {
    throw new Error("User-supplied source dataset is unexpectedly small");
  }
  // Use the same strict Space-Track field validation and compaction as the
  // optional Worker ingest. A malformed row fails the release atomically.
  const omm = gp.rows.map(normalizeSpaceTrackOmm);
  const sourceFiles = [
    { kind: "omm", declaredSource: "Space-Track GP", provenance: gp.provenance,
      file: gp.file, sha256: gp.sha256, bytes: gp.bytes, rows: gp.rows.length, downloadedAt: gpDownloadedAt },
    { kind: "satellites", declaredSource: "SatNOGS DB", provenance: satellites.provenance,
      file: satellites.file, sha256: satellites.sha256, bytes: satellites.bytes, rows: satellites.rows.length,
      downloadedAt: satnogsSatellitesDownloadedAt },
    { kind: "transmitters", declaredSource: "SatNOGS DB", provenance: transmitters.provenance,
      file: transmitters.file, sha256: transmitters.sha256, bytes: transmitters.bytes, rows: transmitters.rows.length,
      downloadedAt: satnogsTransmittersDownloadedAt },
  ];
  return {
    omm,
    satellites: satellites.rows,
    transmitters: transmitters.rows,
    rawOrbitRows: gp.rows.length,
    sourceUpdatedAt: new Date(sourceTime).toISOString(),
    sourceFiles,
    sourceManifestSha256: null,
    orbitalSource: "space-track",
    sourceTimeProvenance: "oldest operator-declared GP or SatNOGS download time; not independently verified",
  };
}

function readPreviousCache(path, publicKey, nextSequence) {
  const file = requireExternalFile(path, "Previous signed cache");
  const size = statSync(file).size;
  if (size < 10 || size > 8 + MAX_SIGNATURE_BYTES + MAX_GZIP_BYTES) {
    throw new Error("Previous signed cache exceeds Android size limits");
  }
  const bytes = readFileSync(file);
  if (bytes.readUInt32BE(0) !== 0x53415431) throw new Error("Previous signed cache has an invalid SAT1 header");
  const signatureLength = bytes.readUInt32BE(4);
  const compressed = bytes.subarray(8 + signatureLength);
  if (signatureLength < 1 || signatureLength > MAX_SIGNATURE_BYTES ||
      compressed.length < 1 || compressed.length > MAX_GZIP_BYTES) {
    throw new Error("Previous signed cache has invalid signature or gzip length");
  }
  let envelope;
  try {
    envelope = JSON.parse(bytes.subarray(8, 8 + signatureLength).toString("utf8"));
  } catch {
    throw new Error("Previous signed cache has an invalid signature envelope");
  }
  if (!envelope || envelope.schemaVersion !== 1 || envelope.algorithm !== "Ed25519" ||
      !Number.isSafeInteger(envelope.sequence) || envelope.sequence < 1 ||
      envelope.sequence >= nextSequence || envelope.sha256 !== sha256(compressed) ||
      typeof envelope.signature !== "string" ||
      !verify(null, compressed, publicKey, Buffer.from(envelope.signature, "base64url"))) {
    throw new Error("Previous signed cache has an invalid signature or sequence");
  }
  let manifest;
  try {
    manifest = JSON.parse(gunzipSync(compressed, { maxOutputLength: MAX_PLAIN_BYTES }).toString("utf8"));
  } catch {
    throw new Error("Previous signed cache cannot be parsed within Android limits");
  }
  if (!manifest || manifest.schemaVersion !== 1 || manifest.sequence !== envelope.sequence ||
      !Array.isArray(manifest.satellites) || manifest.satellites.length < 1 ||
      manifest.satellites.some((satellite) => !satellite || typeof satellite.noradId !== "string") ||
      new Set(manifest.satellites.map((satellite) => satellite.noradId)).size !== manifest.satellites.length) {
    throw new Error("Previous signed cache has an invalid catalog");
  }
  return {
    file: basename(file),
    sha256: sha256(bytes),
    sequence: envelope.sequence,
    distinctOrbits: manifest.satellites.length,
  };
}

/** Builds one immutable local release in an outside-workspace directory. */
export async function buildLocalCache({
  sourceDir,
  spaceTrackGpPath,
  satnogsSatellitesPath,
  satnogsTransmittersPath,
  gpDownloadedAt,
  satnogsSatellitesDownloadedAt,
  satnogsTransmittersDownloadedAt,
  expectedMinimumOrbits,
  previousCachePath,
  outputDir,
  privateKeyPath,
  keyId,
  sequence,
  now = new Date(),
  minimumRows = 1_000,
  minimumDistinctOrbits = 15_000,
  allowStale = false,
  orbitUrls = CELESTRAK_OMM_URLS,
}) {
  if (!Number.isSafeInteger(sequence) || sequence < 1) throw new Error("Sequence must be a positive integer");
  if (!(now instanceof Date) || !Number.isFinite(now.valueOf())) throw new Error("Invalid generation time");
  const hasSpaceTrackFile = [spaceTrackGpPath, satnogsSatellitesPath,
    satnogsTransmittersPath, gpDownloadedAt, satnogsSatellitesDownloadedAt,
    satnogsTransmittersDownloadedAt]
    .some((value) => value !== undefined);
  if ((sourceDir && hasSpaceTrackFile) || (!sourceDir && !hasSpaceTrackFile)) {
    throw new Error("Choose exactly one source mode: saved CelesTrak responses or user-supplied Space-Track GP files");
  }
  if (hasSpaceTrackFile && (!spaceTrackGpPath || !satnogsSatellitesPath ||
      !satnogsTransmittersPath || !gpDownloadedAt || !satnogsSatellitesDownloadedAt ||
      !satnogsTransmittersDownloadedAt || !Number.isSafeInteger(expectedMinimumOrbits) ||
      expectedMinimumOrbits < minimumDistinctOrbits)) {
    throw new Error("Space-Track build requires GP and both SatNOGS files, their UTC download times, and an explicit expected minimum orbit count at least the coverage floor");
  }
  const output = requireNewExternalDirectory(outputDir, "Output");
  const keyFile = requireExternalFile(privateKeyPath, "Signing private key");
  const keyBase64 = readFileSync(keyFile, "utf8").trim();
  const privateKey = createPrivateKey({ key: Buffer.from(keyBase64, "base64"), format: "der", type: "pkcs8" });
  if (privateKey.asymmetricKeyType !== "ed25519") throw new Error("Signing key must be Ed25519 PKCS#8");
  const publicKey = createPublicKey(privateKey);
  const rawPublic = Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url");
  if (rawPublic.byteLength !== 32) throw new Error("Invalid Ed25519 public key");
  const previousCache = previousCachePath ? readPreviousCache(previousCachePath, publicKey, sequence) : null;

  let inputs;
  if (sourceDir) {
    const { ordered, manifestSha256, orbitalSource } = readSavedSources(resolve(sourceDir), now, orbitUrls);
    if (ordered[0].rows.length < minimumRows || ordered.at(-2).rows.length < minimumRows ||
        ordered.at(-1).rows.length < minimumRows) {
      throw new Error("Saved source dataset is unexpectedly small");
    }
    if (orbitalSource === "space-track" &&
        (!Number.isSafeInteger(expectedMinimumOrbits) || expectedMinimumOrbits < minimumDistinctOrbits)) {
      throw new Error("Saved Space-Track build requires an explicit expected minimum orbit count at least the coverage floor");
    }
    const rawOmm = ordered.slice(0, -2).flatMap((item) => item.rows);
    const omm = orbitalSource === "space-track" ? rawOmm.map(normalizeSpaceTrackOmm) : rawOmm;
    if (orbitalSource === "space-track") {
      const actualDistinct = new Set(omm.map((item) => String(Number(item.NORAD_CAT_ID)))).size;
      if (actualDistinct !== ordered[0].source.distinctOrbits) {
        throw new Error("Saved Space-Track distinct-orbit count mismatch");
      }
    }
    inputs = {
      omm, satellites: ordered.at(-2).rows, transmitters: ordered.at(-1).rows,
      rawOrbitRows: rawOmm.length,
      sourceUpdatedAt: new Date(Math.min(...(orbitalSource === "space-track" ? ordered : ordered.slice(0, -2))
        .map((item) => utcTime(item.source.fetchedAt, "Source fetch time")))).toISOString(),
      sourceFiles: ordered.map(({ source }) => source),
      sourceManifestSha256: manifestSha256,
      orbitalSource,
      sourceTimeProvenance: orbitalSource === "space-track"
        ? "source-manifest recorded GP and SatNOGS response completion times; hashes checked at build, origin not independently verified"
        : "recorded response fetch time",
    };
  } else {
    inputs = readUserSpaceTrackSources({
      spaceTrackGpPath, satnogsSatellitesPath, satnogsTransmittersPath,
      gpDownloadedAt, satnogsSatellitesDownloadedAt, satnogsTransmittersDownloadedAt,
      generatedAt: now, minimumRows,
    });
  }
  const { omm, satellites, transmitters, sourceUpdatedAt } = inputs;
  const sourceTime = utcTime(sourceUpdatedAt, "Orbital source time");
  if (!allowStale && now.valueOf() > sourceTime + FRESHNESS_MS) {
    throw new Error("Oldest orbital response exceeds the Android 72-hour reception freshness limit; fetch new sources or use --allow-stale for tracking only");
  }
  const manifest = buildCatalog(omm, satellites, transmitters, sequence, now.toISOString(), inputs.orbitalSource);
  manifest.sourceUpdatedAt = sourceUpdatedAt;
  if (inputs.orbitalSource === "space-track") {
    manifest.attribution.push(inputs.sourceManifestSha256
      ? "Source-manifest labeled Space-Track GP JSON and SatNOGS JSON; recorded response times and file hashes do not independently prove origin or completeness."
      : "User-supplied Space-Track GP JSON and SatNOGS JSON; file origins, download times, and completeness were not independently verified.");
  }
  const previousMinimum = previousCache ? Math.ceil(previousCache.distinctOrbits * 0.9) : 0;
  const requiredOrbits = Math.max(minimumDistinctOrbits, expectedMinimumOrbits ?? 0, previousMinimum);
  if (manifest.satellites.length < requiredOrbits) {
    throw new Error(`Only ${manifest.satellites.length} distinct orbits; minimum ${requiredOrbits}`);
  }
  const plainBytes = Buffer.byteLength(JSON.stringify(manifest));
  const compressed = Buffer.from(await gzipJson(manifest, MAX_PLAIN_BYTES));
  if (compressed.byteLength > MAX_GZIP_BYTES) throw new Error("Catalog exceeds Android compressed size limit");
  const signature = await signCatalog(compressed, keyBase64, keyId, sequence);
  const signatureBytes = Buffer.from(JSON.stringify(signature));
  if (signatureBytes.byteLength > MAX_SIGNATURE_BYTES) throw new Error("Signature envelope exceeds Android size limit");
  if (signature.sha256 !== sha256(compressed) ||
      !verify(null, compressed, publicKey, Buffer.from(signature.signature, "base64url"))) {
    throw new Error("Independent catalog signature verification failed");
  }
  const header = Buffer.alloc(8);
  header.writeUInt32BE(0x53415431, 0);
  header.writeUInt32BE(signatureBytes.byteLength, 4);
  const cache = Buffer.concat([header, signatureBytes, compressed]);
  const summary = {
    schemaVersion: 1,
    sequence,
    keyId,
    publicKeyBase64: rawPublic.toString("base64"),
    generatedAt: manifest.generatedAt,
    sourceUpdatedAt,
    sourceExpiresAt: new Date(sourceTime + FRESHNESS_MS).toISOString(),
    catalogSourceFreshAtBuild: now.valueOf() <= sourceTime + FRESHNESS_MS,
    orbitalSource: inputs.orbitalSource,
    sourceTimeProvenance: inputs.sourceTimeProvenance,
    sourceManifestSha256: inputs.sourceManifestSha256,
    sourceFiles: inputs.sourceFiles,
    expectedMinimumOrbits: expectedMinimumOrbits ?? null,
    previousCache,
    requiredOrbits,
    rawOrbitRows: inputs.rawOrbitRows,
    distinctOrbits: manifest.satellites.length,
    transmitterCount: manifest.satellites.reduce((total, sat) => total + sat.transmitters.length, 0),
    plainBytes,
    gzipBytes: compressed.byteLength,
    cacheBytes: cache.byteLength,
    gzipSha256: signature.sha256,
  };

  const staging = mkdtempSync(join(dirname(output), ".catalog-release-"));
  try {
    for (const [file, bytes] of [
      ["catalog.cache", cache],
      ["catalog.json.gz", compressed],
      ["catalog.sig", signatureBytes],
      ["catalog-public.raw.b64", rawPublic.toString("base64") + "\n"],
      ["summary.json", JSON.stringify(summary, null, 2) + "\n"],
    ]) {
      writeFileSync(join(staging, file), bytes, { flag: "wx", mode: 0o600 });
    }
    renameSync(staging, output);
  } catch (error) {
    rmSync(staging, { recursive: true, force: true });
    throw error;
  }
  return summary;
}

async function main() {
  const action = process.argv[2];
  if (action === "fetch") {
    const { values } = parseArgs({
      args: process.argv.slice(3),
      options: { "source-dir": { type: "string" }, "state-dir": { type: "string" } },
    });
    if (!values["source-dir"]) throw new Error("fetch requires --source-dir");
    const sources = await fetchLocalSources({ sourceDir: values["source-dir"], stateDir: values["state-dir"] });
    console.log(`Saved ${sources.sources.length} responses in ${resolve(values["source-dir"])}`);
  } else if (action === "fetch-space-track") {
    const { values } = parseArgs({
      args: process.argv.slice(3),
      options: { "source-dir": { type: "string" } },
    });
    if (!values["source-dir"]) throw new Error("fetch-space-track requires --source-dir");
    const credentials = {
      identity: process.env.SPACETRACK_IDENTITY ?? "",
      password: process.env.SPACETRACK_PASSWORD ?? "",
    };
    delete process.env.SPACETRACK_IDENTITY;
    delete process.env.SPACETRACK_PASSWORD;
    const sources = await fetchLocalSpaceTrackSources({ sourceDir: values["source-dir"], credentials });
    console.log(JSON.stringify({
      sourceDir: resolve(values["source-dir"]),
      gpRows: sources.sources[0].rows,
      distinctOrbits: sources.sources[0].distinctOrbits,
      gpFetchedAt: sources.sources[0].fetchedAt,
      sourceManifest: "sources.json",
    }, null, 2));
  } else if (action === "build") {
    const { values } = parseArgs({
      args: process.argv.slice(3),
      options: {
        "source-dir": { type: "string" }, "output-dir": { type: "string" },
        "space-track-gp": { type: "string" },
        "satnogs-satellites": { type: "string" },
        "satnogs-transmitters": { type: "string" },
        "gp-downloaded-at": { type: "string" },
        "satnogs-satellites-downloaded-at": { type: "string" },
        "satnogs-transmitters-downloaded-at": { type: "string" },
        "expected-minimum-orbits": { type: "string" },
        "previous-cache": { type: "string" },
        "private-key": { type: "string" }, "key-id": { type: "string" }, "sequence": { type: "string" },
        "allow-stale": { type: "boolean" },
      },
    });
    if ((!values["source-dir"] && !values["space-track-gp"]) ||
        !values["output-dir"] || !values["private-key"] ||
        !values["key-id"] || !/^[1-9]\d*$/.test(values.sequence ?? "")) {
      throw new Error("build requires one source mode, --output-dir, --private-key, --key-id, and --sequence N");
    }
    const summary = await buildLocalCache({
      sourceDir: values["source-dir"], outputDir: values["output-dir"],
      spaceTrackGpPath: values["space-track-gp"],
      satnogsSatellitesPath: values["satnogs-satellites"],
      satnogsTransmittersPath: values["satnogs-transmitters"],
      gpDownloadedAt: values["gp-downloaded-at"],
      satnogsSatellitesDownloadedAt: values["satnogs-satellites-downloaded-at"],
      satnogsTransmittersDownloadedAt: values["satnogs-transmitters-downloaded-at"],
      expectedMinimumOrbits: values["expected-minimum-orbits"] === undefined
        ? undefined : Number(values["expected-minimum-orbits"]),
      previousCachePath: values["previous-cache"],
      privateKeyPath: values["private-key"], keyId: values["key-id"], sequence: Number(values.sequence),
      allowStale: values["allow-stale"] === true,
    });
    console.log(JSON.stringify({
      outputDir: resolve(values["output-dir"]), distinctOrbits: summary.distinctOrbits,
      sourceUpdatedAt: summary.sourceUpdatedAt, sourceExpiresAt: summary.sourceExpiresAt,
      catalogSourceFreshAtBuild: summary.catalogSourceFreshAtBuild,
      publicKeyBase64: summary.publicKeyBase64,
    }, null, 2));
  } else {
    throw new Error("Usage: local-cache.mjs fetch --source-dir NEW_DIR [--state-dir DIR] | fetch-space-track --source-dir NEW_DIR (credentials from SPACETRACK_IDENTITY and SPACETRACK_PASSWORD) | build (--source-dir DIR | --space-track-gp FILE --satnogs-satellites FILE --satnogs-transmitters FILE --gp-downloaded-at UTC --satnogs-satellites-downloaded-at UTC --satnogs-transmitters-downloaded-at UTC) --output-dir NEW_DIR --private-key FILE --key-id ID --sequence N [--expected-minimum-orbits N] [--previous-cache FILE] [--allow-stale]");
  }
}

if (process.argv[1] && resolve(process.argv[1]) === SCRIPT_PATH) {
  main().catch((error) => { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; });
}
