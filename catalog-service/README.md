# OrbitScope catalog service

This Cloudflare Worker publishes a signed, compressed orbital catalog for the Android app. By default, every two hours it requests CelesTrak OMM JSON for Active, SatNOGS, Last 30 Days' Launches, Analyst, three named debris groups, GEO Protected Zone Plus, and three documented name substring queries for debris (`DEB`), rocket bodies (`R/B`), and coolant (`COOLANT`). When both optional Space-Track secrets are configured, it uses one authenticated Space-Track GP JSON query instead of the CelesTrak queries. Both paths join SatNOGS satellite and transmitter records when available and apply the checked-in profiles in `src/profiles.ts`. Objects without radio metadata remain trackable. The Worker sends no phone location to an upstream source.

These [CelesTrak Current GP groups](https://celestrak.org/NORAD/elements/index.php?FORMAT=json) and [documented NAME queries](https://celestrak.org/NORAD/documentation/gp-data-formats.php) broaden coverage beyond active satellites. In one local fetch on September 24, 2026, `NAME=DEB` added 7,254 distinct orbits and `NAME=R%2FB` added another 1,734 beyond the earlier eight groups, producing 30,082 unique orbits. A separate `NAME=COOLANT` fetch on September 25 returned 70 orbits, all absent from that signed 30,082-orbit cache. A local build from those saved responses produced 30,152 distinct orbits, 15.83 MB JSON, and 2.33 MB gzip, within the Android client's current size limits. That result was signed and independently verified as a temporary local cache; its conservative `sourceUpdatedAt` is the oldest saved GP response time, 2026-09-24T12:18:40.381Z, so reception eligibility still expires with the age of the catalog or each selected orbit. The new coolant records are tracking-only. These queries are **not an all-object feed**: CelesTrak documents named groups, NAME matches, and individual catalog-number queries, but no all-current-GP bulk query. Its [special data request form](https://celestrak.org/NORAD/archives/request.php) is capped and manually submitted, so it cannot fill that gap for automated refreshes. The app and service must not claim to track every object with publicly available orbital elements. Use OMM JSON rather than legacy TLE text because [new six-digit catalog numbers no longer fit CelesTrak's TLE format](https://celestrak.org/NORAD/elements/index.php?FORMAT=json). All these additional objects are tracking-only unless a downlink has an exact curated receive profile.

[Space-Track's GP class](https://www.space-track.org/documentation) supplies the newest element set for each publicly available tracked object. The same documentation states that USSPACECOM has given express blanket approval to redistribute basic SSA data, including OMMs, with appropriate citation; this qualifies the general transfer restriction in its User Agreement. A registered account is still required. The optional ingest requests the unfiltered latest set (`class/gp/format/json`), retaining older epochs and records with source-reported decay dates rather than silently excluding available IDs. It preserves validated `DECAY_DATE` metadata; Android uses it to label historical elements, reject current reception, and withhold predictions from the conservative UTC start of that date. A decay date does not establish physical reentry, an exact event time, or its cause. The source does not request `gp_history` or every past element set. The shared normalization and offline signing path have now processed a user-supplied 69,438-record GP file without dropping IDs; the authenticated CLI fetch and Worker refresh still have mock tests only. The Galaxy A30 verified and installed that exact-count release, and all eight instrumentation tests passed. That supplied-file result does not independently prove upstream completeness or production Worker performance.

For tracking without deploying the service, the Android Sky screen can import a user-selected `.csv` GP file from CelesTrak or an approved Space-Track account, as well as OMM `.json` and legacy TLE text. The CSV parser accepts OMM keyword columns, quoted names, and nine-digit NORAD IDs; raw Space-Track JSON timestamps with a UTC date/time space are also accepted. These files add **tracking-only** orbits and never enable radio reception. The app does not log in to Space-Track or download a bulk feed on the user's behalf. [CelesTrak documents CSV and JSON GP formats](https://celestrak.org/NORAD/documentation/gp-data-formats.php); [Space-Track documents its GP class and account requirement](https://www.space-track.org/documentation). A local file can fill gaps in the signed catalog, but its presence does not prove that every publicly available orbit was included.

The service is not deployed by this repository. It needs a Cloudflare account, an R2 bucket, and an Ed25519 signing key before a production app can use it.

## Local checks

```powershell
cd catalog-service
npm.cmd ci
npm.cmd run check
npm.cmd test
```

`fixtures/demo-catalog.json` is a small, **unsigned, historical demo**. The identical Android asset is `../app/src/main/assets/sample_catalog.json`. Neither file may be accepted as a production catalog, and its 2026-09-22 orbital epochs will become stale.

## Local signed cache for a phone field check

`scripts/local-cache.mjs` makes a signed Android `catalog.cache` **without deploying the Worker**. It needs Node 24, an Ed25519 private key outside this workspace, and one source set: the configured CelesTrak responses, a locally fetched Space-Track GP response from an approved account, or an authorized user-supplied Space-Track GP JSON file. Each source set includes SatNOGS metadata. Keep the same key for later refreshes and increase `--sequence`; if that key is lost, generate a new one and rebuild the Android APK with its new public key. The script never stores the private key in a release or prints it.

Run these PowerShell commands from `catalog-service` after `npm.cmd ci`. Choose new source and release directories outside the repository for each run:

```powershell
$keys = Join-Path $env:LOCALAPPDATA "satellite-catalog-keys"
node scripts/generate-key.mjs $keys  # First run only; keep this key for later refreshes.
$sources = Join-Path $env:TEMP ("satellite-sources-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$release = Join-Path $env:TEMP ("satellite-release-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
node --disable-warning=ExperimentalWarning --experimental-strip-types scripts/local-cache.mjs fetch --source-dir $sources
node --disable-warning=ExperimentalWarning --experimental-strip-types scripts/local-cache.mjs build --source-dir $sources --output-dir $release --private-key (Join-Path $keys "catalog-private.pkcs8.b64") --key-id local-v1 --sequence 1
```

### Local fetch with an approved Space-Track account

The `fetch-space-track` command is an alternative to `fetch`. It reads `SPACETRACK_IDENTITY` and `SPACETRACK_PASSWORD` **only from its process environment**; never place them in command arguments, a file in this repository, or chat. Enter them interactively on a trusted computer and remove them from the PowerShell environment when the command finishes. The Node process also removes its inherited copies immediately after reading them. The command does not print credentials or cookies, and it saves no login response. An account holder must follow [Space-Track's API use guidelines](https://www.space-track.org/documentation). The single GP query is `class/gp/format/json`, with no epoch or decay filter. It retrieves each available latest record, including stale and decayed objects; it does not sweep historical element sets. Space-Track permits GP retrieval once per hour; this command writes a durable attempt record **before login** and permits at most one attempt every two hours, including failed attempts. It makes one GP query and two SatNOGS API requests per permitted attempt. Other uses of the same account also count toward Space-Track's limits. Do not delete or relocate the state directory to bypass the interval.

```powershell
$keys = Join-Path $env:LOCALAPPDATA "satellite-catalog-keys"  # Use the existing key if one has been generated.
$sources = Join-Path $env:TEMP ("satellite-space-track-sources-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$release = Join-Path $env:TEMP ("satellite-space-track-release-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$account = Get-Credential -Message "Approved Space-Track account"
$env:SPACETRACK_IDENTITY = $account.UserName
$env:SPACETRACK_PASSWORD = $account.GetNetworkCredential().Password
try {
  node --disable-warning=ExperimentalWarning --experimental-strip-types scripts/local-cache.mjs fetch-space-track --source-dir $sources
  if ($LASTEXITCODE -ne 0) { throw "Space-Track fetch failed; keep the two-hour attempt gate" }
} finally {
  Remove-Item Env:SPACETRACK_IDENTITY -ErrorAction SilentlyContinue
  Remove-Item Env:SPACETRACK_PASSWORD -ErrorAction SilentlyContinue
  $account = $null
}

$expectedMinimumOrbits = 30000  # Example only: choose from independent expected coverage, not the downloaded row count.
node --disable-warning=ExperimentalWarning --experimental-strip-types scripts/local-cache.mjs build --source-dir $sources --expected-minimum-orbits $expectedMinimumOrbits --output-dir $release --private-key (Join-Path $keys "catalog-private.pkcs8.b64") --key-id local-v1 --sequence 2
if ($LASTEXITCODE -ne 0) { throw "Catalog build failed" }
```

Use a **new** `$sources` and `$release` directory for each run, and advance the sequence from the last release signed by that key. For a refresh, add `--previous-cache` with that release's `catalog.cache`; the builder verifies its signature and requires at least 90% of its orbit count. The fetch creates `space-track-gp.json`, both SatNOGS JSON files, and `sources.json` together using an atomic directory rename. It downloads SatNOGS metadata before making the rate-limited GP request, and timestamps each response when its HTTP body finishes before local parsing. It validates the GP records, requires at least 15,000 distinct NORAD IDs, rejects a GP response over 128 MiB or either SatNOGS response over 30 MB, and records each response's SHA-256, row count, and actual local completion time. The builder rechecks all three hashes, counts, and the GP distinct count, compares the complete normalized source-ID set with the built catalog-ID set, then uses the **oldest** recorded completion time as `sourceUpdatedAt`. Missing or substituted IDs fail even when the total count matches. Review `sources.json` and the signed release's `summary.json`: compare the recorded GP distinct count with `summary.json`'s `distinctOrbits` and with an independently expected count. Exact retention proves only that the supplied response's valid IDs survive; neither it, the 15,000 floor, nor a successful signature proves that upstream returned every public orbit. The GP may also exceed the Android client's 100,000-record, 40 MB JSON, or 6 MB gzip catalog limits; the builder refuses the entire release instead of truncating it. These are payload ceilings, not proof that the Worker or phone has enough memory to process a full catalog and its pass schedule. The authenticated CLI fetch has mock tests only; it has not been exercised with a real account. The supplied-file build below does not test this login or HTTP transport.

The state files are in the per-user `~/.cache/satellite-eavesdropper` directory (or the corresponding Windows home directory). A failed or interrupted `fetch-space-track` attempt still consumes the two-hour interval. If a terminated process leaves `space-track-fetch.lock`, confirm that its recorded PID is gone before removing only the lock; retain `space-track-last-attempt.json`. No partial source directory is published on failure. The fetch command saves source files locally and does not deploy the Worker, install an app, or send credentials to SatNOGS.

To build **offline** from a full GP JSON file already obtained with an approved Space-Track account, supply that file and separate SatNOGS satellites/transmitters JSON arrays. All three files must be outside this repository. Record the actual UTC download time for **each** file; those three `--*-downloaded-at` values are mandatory and must not be replaced with the build time. State a defensible minimum distinct-orbit count for the expected GP response with `--expected-minimum-orbits`. A 15,000-orbit built-in floor is only a last-resort sanity check, not a claim of full coverage. This path never logs in, downloads GP data, or sends credentials anywhere. [Space-Track describes the latest-record GP class and account requirement](https://www.space-track.org/documentation); [SatNOGS documents its DB API](https://docs.satnogs.org/projects/satnogs-db/en/latest/api.html).

```powershell
$gp = "C:\path\to\authorized-gp.json"
$satellites = "C:\path\to\satnogs-satellites.json"
$transmitters = "C:\path\to\satnogs-transmitters.json"
$gpDownloadedAt = "YYYY-MM-DDTHH:MM:SSZ"  # Actual GP download time in UTC.
$satellitesDownloadedAt = "YYYY-MM-DDTHH:MM:SSZ"  # Actual SatNOGS satellites download time.
$transmittersDownloadedAt = "YYYY-MM-DDTHH:MM:SSZ"  # Actual SatNOGS transmitters download time.
$expectedMinimumOrbits = 30000  # Example only: choose a floor for the expected full GP response.
$release = Join-Path $env:TEMP ("satellite-space-track-release-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$buildArgs = @("--disable-warning=ExperimentalWarning", "--experimental-strip-types", "scripts/local-cache.mjs", "build", "--space-track-gp", $gp, "--satnogs-satellites", $satellites, "--satnogs-transmitters", $transmitters, "--gp-downloaded-at", $gpDownloadedAt, "--satnogs-satellites-downloaded-at", $satellitesDownloadedAt, "--satnogs-transmitters-downloaded-at", $transmittersDownloadedAt, "--expected-minimum-orbits", $expectedMinimumOrbits, "--output-dir", $release, "--private-key", (Join-Path $keys "catalog-private.pkcs8.b64"), "--key-id", "local-v1", "--sequence", "2")
node @buildArgs
```

The builder uses the same strict Space-Track GP normalization as the optional Worker ingest, retains the latest element set per NORAD ID, requires at least 15,000 distinct valid orbits **and** the stated expected minimum, compares every normalized source ID with the built catalog, and enforces the Android 100,000-record, 40 MB JSON, and 6 MB gzip catalog limits without dropping IDs. The raw GP input is capped at 128 MiB; each SatNOGS input is capped at 30 MB. The release summary records each file's basename, byte count, row count, SHA-256 hash, and declared download time. `sourceUpdatedAt` is the **oldest of those three operator-declared download times**, not the build time or maximum orbital epoch. The builder rejects a future or more-than-72-hour-old time unless `--allow-stale` is explicitly used for tracking only. A local file and its declared time cannot independently prove origin, actual download time, or completeness; the manifest and `summary.json` state those limits. Individual old orbits can become ineligible for reception earlier than the catalog's source time. The user-supplied files do not need to be copied into the release and are never uploaded by this script.

### Verified supplied-file build: September 26, 2026

The user supplied `space-track-gp.json` after downloading the unfiltered GP page in a browser. The local offline builder normalized all 69,438 rows successfully, joined two freshly fetched SatNOGS JSON responses, and produced a sequence-2 signed release. The source-ID set and the signed catalog-ID set are identical: 69,438 unique IDs, zero omissions, and zero substitutions. Every one of the prior local CelesTrak cache's 30,152 IDs is included; the new file adds another 39,286 IDs.

| Artifact | Exact size |
| --- | ---: |
| Raw supplied GP JSON | 78,864,118 bytes |
| Signed catalog JSON before gzip | 38,445,480 bytes |
| Signed gzip payload | 5,690,345 bytes |

The raw file exceeded the former 64 MiB input cap, so the local GP input ceiling was raised to 128 MiB. The generated catalog fits the unchanged Android 100,000-record, 40 MB JSON, and 6 MB gzip limits. No records were removed to meet those limits. Its source includes old epochs and 34,081 source-reported decay dates; these records remain available for historical browsing with the Android date cutoff described above.

The GP `downloadedAt` declaration was approximated from the observed supplied file's modification time, `2026-09-26T14:43:49.271Z`; it is not an independently verified browser acquisition time. The two SatNOGS API responses completed locally at `2026-09-26T14:49:04.094512Z` and `2026-09-26T14:49:06.269168Z`. Consequently `sourceUpdatedAt` is the earlier declared GP time. The file's origin and the completeness of the remote query cannot be established from that timestamp, a signature, or the retained ID count. This verifies the supplied-file ingestion and signing path. The Android 11 Galaxy A30 then passed all eight instrumentation tests in 20.586 s, including exact-count signature verification, same-graph reuse, altered-cache rejection/restoration, document imports, synthetic JNI checks, and simulated foreground location. The production day-schedule benchmark processed the first 128 records in 666 ms, reporting 310 passes and no failures while honoring historical-date cutoffs. A separate September 27 phone run then processed all 69,438 records and reported 234,549 predicted passes with 31 per-orbit propagation failures, without a new crash. Known source-dated historical records were retained but skipped for current prediction. The complete result survived a Sky → Location → Sky round trip. The separate three-time desktop diagnostic also found 31 failures and reproduced them from the raw source TLEs; see [TEST_RESULTS.md](../TEST_RESULTS.md) for the limits. Live authenticated CLI/Worker fetching remains unverified. No service was deployed.

For a later Space-Track file build, set `$priorRelease` to a previous release signed by the **same key**, increase `--sequence`, and add this argument before running `node @buildArgs`:

```powershell
$buildArgs += @("--previous-cache", (Join-Path $priorRelease "catalog.cache"))
node @buildArgs
```

The builder verifies that cache's signature and requires at least 90% of its orbit count as well as the explicit expected minimum. Use a new release directory each time. A first release has no prior cache, so the declared expected minimum is required even then; choose it from independent knowledge of the intended GP response, not from a possibly truncated input file.

On later CelesTrak runs, skip key generation, use new source/release directories, and increment the sequence. For a later Space-Track file build, supply current files, use a new release directory, and increment the sequence. Keep a protected backup of the external private key; if it is lost, the existing APK cannot verify a newly signed cache until rebuilt with a new public key. The CelesTrak fetch stage writes `sources.json` with the URL, fetch time, row count, and SHA-256 hash for every response. Its build stage rechecks those hashes and counts. Both build modes join the data using the same catalog builder as the Worker, enforce the Android 100,000-record, 40 MB JSON, and 6 MB gzip limits, sign the exact gzip bytes, and independently verify the signature. The release contains `catalog.cache`, `catalog.json.gz`, `catalog.sig`, `catalog-public.raw.b64`, and `summary.json`. The `catalog.cache` file uses the Android client's `SAT1` wrapper. Pin the release's raw public key in the APK with `-PcatalogPublicKeyBase64=<contents of catalog-public.raw.b64>`; the key in the APK must match the cache. This workflow creates local files only: it does not install an APK, change phone data, or deploy a service.

To seed a **debug app** on a directly connected Windows phone, run this from `catalog-service` after building the release. `adb.exe` must be on `PATH`; use the connected device's serial with `adb.exe -s SERIAL` if more than one device appears. Confirm the Sky count and Location's verified-cache label after launch; review `summary.json` for the source timestamp.

```powershell
$cache = Join-Path $release "catalog.cache"
$publicKey = (Get-Content -Raw (Join-Path $release "catalog-public.raw.b64")).Trim()
Set-Location ..
.\gradlew.bat :app:assembleDebug "-PcatalogPublicKeyBase64=$publicKey"
if ($LASTEXITCODE -ne 0) { throw "Android build failed" }
function adbChecked { & adb.exe @args; if ($LASTEXITCODE -ne 0) { throw "adb failed: $($args -join ' ')" } }
adbChecked install -r app\build\outputs\apk\debug\app-debug.apk
adbChecked shell am force-stop org.satelliteeavesdropper.app
adbChecked push $cache /data/local/tmp/catalog.cache
adbChecked shell chmod 644 /data/local/tmp/catalog.cache
adbChecked shell run-as org.satelliteeavesdropper.app mkdir -p files
adbChecked shell run-as org.satelliteeavesdropper.app cp /data/local/tmp/catalog.cache files/catalog.cache.new
$localBytes = (Get-Item $cache).Length
$remoteCount = ((adbChecked shell run-as org.satelliteeavesdropper.app wc -c files/catalog.cache.new).Trim() -split '\s+')[0]
if ([long]$remoteCount -ne $localBytes) { throw "Catalog cache transfer truncated: $remoteCount of $localBytes bytes" }
adbChecked shell run-as org.satelliteeavesdropper.app mv files/catalog.cache.new files/catalog.cache
adbChecked shell am start -n org.satelliteeavesdropper.app/.MainActivity
```

The `adbChecked` helper stops on an adb error. This copies a local signed cache into the debug app's private storage; it is not a service deployment. Use `adb push` for the binary file: on the tested Windows adb/WSL setup, piping binary bytes through `adb shell -T` silently truncated the cache to 566 bytes.

The oldest OMM response fetch time becomes `sourceUpdatedAt`; generating a new signature cannot make old elements fresh. By default, `build` refuses a response that is already older than the app's 72-hour catalog freshness limit or dated after the build time. `--allow-stale` permits an old release for **tracking only** and sets `catalogSourceFreshAtBuild: false` in `summary.json`; it does not override Android's reception gate. Each selected orbit's own epoch may make reception ineligible sooner. Review `summary.json` for counts, timestamps, source hashes, expiry, and public key before using a release for a field check.

The fetch stage persists its last CelesTrak attempt before making any request in the per-user default state directory (`~/.cache/satellite-eavesdropper` on Unix, the corresponding home directory on Windows). A failed or interrupted attempt still consumes the two-hour interval; changing `--state-dir` to retry defeats the intended rate guard. It downloads each configured response sequentially and stops on the first HTTP, format, or size error. If an interrupted process leaves `celestrak-fetch.lock`, first confirm its recorded PID is no longer running, then remove only that lock file. Keep `celestrak-last-attempt.json`; wait until its two-hour interval has elapsed before fetching again. A partial source directory is not reusable as a complete set. Saved responses can be built again without network access if they have a complete `sources.json` containing the exact configured URLs and each response's basename, `fetchedAt` UTC time, lowercase SHA-256 hash, and row count. Keep that metadata with the JSON files; the builder rejects missing, changed, duplicate, or future-dated entries.

## Deployment setup

1. Create the R2 bucket named in `wrangler.toml` with `npx.cmd wrangler r2 bucket create satellite-eavesdropper-catalog` (or change the configured bucket name).
2. Generate an Ed25519 key pair into a directory outside this workspace:

   ```powershell
   node scripts/generate-key.mjs "$env:TEMP\satellite-catalog-keys"
   ```

   The script refuses to put keys inside the workspace and refuses to overwrite an existing pair. Keep `catalog-private.pkcs8.b64` secret. `catalog-public.raw.b64` contains the 32-byte raw public key, base64-encoded as expected by the Android client. Store a protected backup of the private key before relying on this service.
3. Set `SIGNING_KEY_ID` in `wrangler.toml` to a stable version label. Store the base64 PKCS#8 private key as a Worker secret, without putting it in the config or source tree:

   ```powershell
   Get-Content -Raw "$env:TEMP\satellite-catalog-keys\catalog-private.pkcs8.b64" |
     npx.cmd wrangler secret put CATALOG_SIGNING_KEY_PKCS8_BASE64
   ```

4. Pin the raw public key in the Android app. Only then deploy with `npx.cmd wrangler deploy` and configure the app's catalog base URL. The current Android client trusts one key and validates the signature; it does not yet support a multi-key rotation window or enforce `keyId`. Before production key rotation, add a versioned keyring to the client and ship it before changing the Worker secret and key ID. Older clients would otherwise keep only their last verified cache.

### Optional Space-Track source

An approved Space-Track account can provide broader GP coverage. Store its username and password as separate Worker secrets; leave both unset to use the default CelesTrak source. Never put them in `wrangler.toml`, the Android app, source files, or logs.

```powershell
npx.cmd wrangler secret put SPACETRACK_IDENTITY
npx.cmd wrangler secret put SPACETRACK_PASSWORD
```

The Worker reads Space-Track's public login form, posts its CSRF token with the secret credentials, carries the returned session cookies, and makes one GP JSON request per scheduled refresh. Its two-hour attempt gate is stricter than [Space-Track's GP limit of once per hour](https://www.space-track.org/documentation). A changed login flow, non-JSON GP response, missing core orbital value, invalid nonempty decay date, or catalog beyond Android's 100,000-record, 40 MB JSON, or 6 MB gzip limits fails the refresh and retains the last signed release. The Space-Track path requires at least 15,000 distinct valid orbits after deduplication. When replacing a signed Space-Track release, it also requires at least 90% of that release's orbit count. The 90% comparison is skipped on the first transition from CelesTrak or a legacy pointer without source metadata, because a different source's count does not establish the expected GP ID set; the 15,000 floor still applies. The builder additionally checks exact source-ID retention. These checks catch omitted or substituted records during the build and some partial responses, but **do not prove upstream completeness**. Null or absent decay metadata is omitted, while a nonempty invalid `DECAY_DATE` rejects the refresh. Other nullable or invalid optional GP metadata is omitted; when drag terms are absent, the Android propagator defaults them to zero. The source attribution names USSPACECOM 18 SDS via Space-Track.org. The shared normalization and signing code has processed the supplied 69,438-record file, and Android verified the resulting exact-count cache. Live account authentication, the Worker refresh and R2 publishing path, and production resource limits still require validation before production use. The offline build does not test those integrations.

For a local scheduled run, use `npx.cmd wrangler dev --test-scheduled`, then request `http://localhost:8787/cdn-cgi/local/scheduled`. Local development uses a separate R2 state and secret. The first upstream refresh may take a while; `GET /v1/status` reports whether a catalog has been published. Do not repeatedly trigger the scheduled route: [CelesTrak permits one download per source update, roughly every two hours](https://celestrak.org/usage-policy.php). Group requests run sequentially and stop on a non-200 response, including redirects; a failed refresh retains the last published catalog and is not retried during the same two-hour interval.

## HTTP contract

| Endpoint | Response |
| --- | --- |
| `GET /v1/catalog.json.gz` | Gzip bytes with `Content-Type: application/gzip`, `X-Catalog-Sequence`, and a SHA-256 ETag. There is deliberately no `Content-Encoding` header. |
| `GET /v1/catalog.sig?sequence=N` | JSON `{schemaVersion,sequence,algorithm,keyId,sha256,signature}`. `signature` is base64url Ed25519 over the **exact gzip bytes**. |
| `GET /v1/status` | Availability, current sequence, orbit source (`orbitalSource`), counts, timestamps, key ID, and last refresh outcome. Older releases without source metadata report `null`. |

The app should download the gzip response, read `X-Catalog-Sequence`, request the signature for that exact sequence, check the SHA-256 digest, verify Ed25519 with the pinned public key, then decompress and validate the schema. If verification or parsing fails, keep the previously verified catalog. Both release endpoints support `HEAD` and `If-None-Match`. `?sequence=N` also works on the gzip endpoint to resolve a concurrent update.

`schemaVersion` is currently `1`. NORAD IDs, including `omm.NORAD_CAT_ID`, are decimal strings to support nine-digit IDs. Each satellite includes orbital OMM fields, name, aliases, and downlinks. The Space-Track path retains the fields needed for Android propagation and object identification, and omits duplicate TLE text, database fields, and unused OMM metadata to control catalog size. Each downlink includes `frequencyHz`, `bandwidthHz`, `mode`, `baud`, `status`, `verifiedAt`, `decoderId`, `captureRateSps`, `antenna`, `policy`, and `evidenceUrls`.

For scheduled Worker releases, `sourceUpdatedAt` is the successful upstream refresh time and is currently the same as `generatedAt`. A local cache assembled from saved responses uses the oldest GP fetch time instead. Neither value is the maximum orbital `EPOCH`: current GP data can contain individual future-dated epochs. The Android receiver also checks the chosen satellite's own epoch before reception.

`decoderId` is one of `AUDIO_NFM`, `AX25_AFSK1200`, `METEOR_LRPT_72K`, `METEOR_LRPT_80K`, `CHANNEL_IQ`, or `null`. It can be non-null only when a curated profile exactly matches the SatNOGS transmitter UUID, frequency, modulation, and baud, and SatNOGS marks it active and confirmed. Uncurated entries receive `policy: "restricted"` and `decoderId: null`. The initial curated profiles cover ISS VHF voice and APRS plus two Meteor LRPT entries; more may be added after source review. `verifiedAt` is the SatNOGS metadata update time for a curated profile, **not** proof that it was heard on air at that time.

The Worker writes versioned gzip and signature objects to R2, then switches a small `catalog/current.json` pointer. If any upstream fetch, normalization, signing, size check, or write fails, that pointer stays on the previous valid release. A separate attempt record prevents a failed cycle from downloading either orbit source again within two hours. Old versioned releases are retained so an app can finish retrieving a matching gzip/signature pair across a publish boundary; storage retention and cleanup should be added before long-term production operation.

## Data sources and rights

- [CelesTrak GP/OMM query format](https://celestrak.org/NORAD/documentation/gp-data-formats.php) and [usage policy](https://celestrak.org/usage-policy.php).
- [Space-Track GP documentation, rate guidance, and Basic SSA redistribution terms](https://www.space-track.org/documentation).
- [SatNOGS DB API](https://docs.satnogs.org/projects/satnogs-db/en/latest/api.html) and [CC BY-SA 4.0 data attribution](https://db.satnogs.org/about/).
- [ARISS ISS packet frequency](https://www.ariss.org/contact-the-iss.html).

The manifest carries attribution strings for display in the app. The service code is GPL-3.0-only; derived SatNOGS data remains subject to CC BY-SA 4.0.
