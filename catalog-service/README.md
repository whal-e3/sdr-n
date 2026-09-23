# Satellite Eavesdropper catalog service

This Cloudflare Worker publishes a signed, compressed satellite catalog for the Android app. Every two hours it requests CelesTrak OMM JSON for the SatNOGS group, joins SatNOGS satellite and transmitter records by NORAD ID, and applies the checked-in profiles in `src/profiles.ts`. It sends no phone location to either source.

The service is not deployed by this repository. It needs a Cloudflare account, an R2 bucket, and an Ed25519 signing key before a production app can use it.

## Local checks

```powershell
cd catalog-service
npm.cmd ci
npm.cmd run check
npm.cmd test
```

`fixtures/demo-catalog.json` is a small, **unsigned, historical demo**. The identical Android asset is `../app/src/main/assets/sample_catalog.json`. Neither file may be accepted as a production catalog, and its 2026-09-22 orbital epochs will become stale.

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

For a local scheduled run, use `npx.cmd wrangler dev --test-scheduled`, then request `http://localhost:8787/cdn-cgi/local/scheduled`. Local development uses a separate R2 state and secret. The first upstream refresh may take a while; `GET /v1/status` reports whether a catalog has been published. Do not repeatedly trigger the scheduled route: [CelesTrak permits one download per source update, roughly every two hours](https://celestrak.org/usage-policy.php).

## HTTP contract

| Endpoint | Response |
| --- | --- |
| `GET /v1/catalog.json.gz` | Gzip bytes with `Content-Type: application/gzip`, `X-Catalog-Sequence`, and a SHA-256 ETag. There is deliberately no `Content-Encoding` header. |
| `GET /v1/catalog.sig?sequence=N` | JSON `{schemaVersion,sequence,algorithm,keyId,sha256,signature}`. `signature` is base64url Ed25519 over the **exact gzip bytes**. |
| `GET /v1/status` | Availability, current sequence, counts, timestamps, key ID, and last refresh outcome. |

The app should download the gzip response, read `X-Catalog-Sequence`, request the signature for that exact sequence, check the SHA-256 digest, verify Ed25519 with the pinned public key, then decompress and validate the schema. If verification or parsing fails, keep the previously verified catalog. Both release endpoints support `HEAD` and `If-None-Match`. `?sequence=N` also works on the gzip endpoint to resolve a concurrent update.

`schemaVersion` is currently `1`. NORAD IDs, including `omm.NORAD_CAT_ID`, are decimal strings to support nine-digit IDs. Each satellite includes full scalar OMM fields as supplied by CelesTrak, name, aliases, and downlinks. Each downlink includes `frequencyHz`, `bandwidthHz`, `mode`, `baud`, `status`, `verifiedAt`, `decoderId`, `captureRateSps`, `antenna`, `policy`, and `evidenceUrls`.

`decoderId` is one of `AUDIO_NFM`, `AX25_AFSK1200`, `METEOR_LRPT_72K`, `METEOR_LRPT_80K`, `CHANNEL_IQ`, or `null`. It can be non-null only when a curated profile exactly matches the SatNOGS transmitter UUID, frequency, modulation, and baud, and SatNOGS marks it active and confirmed. Uncurated entries receive `policy: "restricted"` and `decoderId: null`. The initial curated profiles cover ISS VHF voice and APRS plus two Meteor LRPT entries; more may be added after source review. `verifiedAt` is the SatNOGS metadata update time for a curated profile, **not** proof that it was heard on air at that time.

The Worker writes versioned gzip and signature objects to R2, then switches a small `catalog/current.json` pointer. If any upstream fetch, normalization, signing, or write fails, that pointer stays on the previous valid release. A separate attempt record prevents a failed cycle from downloading CelesTrak again within two hours. Old versioned releases are retained so an app can finish retrieving a matching gzip/signature pair across a publish boundary; storage retention and cleanup should be added before long-term production operation.

## Data sources and rights

- [CelesTrak GP/OMM query format](https://celestrak.org/NORAD/documentation/gp-data-formats.php) and [usage policy](https://celestrak.org/usage-policy.php).
- [SatNOGS DB API](https://docs.satnogs.org/projects/satnogs-db/en/latest/api.html) and [CC BY-SA 4.0 data attribution](https://db.satnogs.org/about/).
- [ARISS ISS packet frequency](https://www.ariss.org/contact-the-iss.html).

The manifest carries attribution strings for display in the app. The service code is GPL-3.0-only; derived SatNOGS data remains subject to CC BY-SA 4.0.
