import { curatedProfileFor } from "./profiles.ts";
import type {
  CatalogManifestV1,
  CatalogSatellite,
  CatalogTransmitter,
  OmmRecord,
  OmmValue,
  SatnogsSatellite,
  SatnogsTransmitter,
  TransmitterStatus,
} from "./types.ts";

// Keep this aligned with Android CatalogParser.MAX_SATELLITES. Exceeding a
// payload ceiling rejects the whole release instead of dropping source IDs.
export const ANDROID_MAX_SATELLITES = 100_000;

const REQUIRED_OMM_NUMBERS = [
  "MEAN_MOTION",
  "ECCENTRICITY",
  "INCLINATION",
  "RA_OF_ASC_NODE",
  "ARG_OF_PERICENTER",
  "MEAN_ANOMALY",
] as const;
const OPTIONAL_OMM_NUMBERS = [
  "BSTAR",
  "MEAN_MOTION_DOT",
  "MEAN_MOTION_DDOT",
  "EPHEMERIS_TYPE",
  "ELEMENT_SET_NO",
  "REV_AT_EPOCH",
] as const;

function objectValue(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Expected an object in an upstream data set");
  }
  return value as Record<string, unknown>;
}

function noradId(value: unknown): string | null {
  if (typeof value === "number" && Number.isSafeInteger(value) && value > 0 && value <= 999_999_999) {
    return String(value);
  }
  if (typeof value === "string" && /^\d{1,9}$/.test(value)) {
    const normalized = value.replace(/^0+(?=\d)/, "");
    return normalized === "0" ? null : normalized;
  }
  return null;
}

function isoUtc(value: unknown): string | null {
  if (typeof value !== "string" || value.length < 10) return null;
  const parsed = new Date(/(?:Z|[+-]\d\d:\d\d)$/.test(value) ? value : `${value}Z`);
  return Number.isFinite(parsed.valueOf()) ? parsed.toISOString() : null;
}

function numeric(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/** Every distinct source ID must survive a latest-GP catalog build exactly once. */
export function assertSpaceTrackCatalogCoverage(
  source: readonly unknown[],
  satellites: readonly Pick<CatalogSatellite, "noradId">[],
): void {
  const expected = new Set<string>();
  for (const item of source) {
    const id = noradId(objectValue(item).NORAD_CAT_ID);
    if (!id) throw new Error("Space-Track coverage check encountered an invalid source ID");
    expected.add(id);
  }
  const actual = new Set(satellites.map((satellite) => satellite.noradId));
  if (actual.size !== satellites.length || actual.size !== expected.size ||
      [...expected].some((id) => !actual.has(id))) {
    throw new Error("Space-Track catalog does not retain every distinct source ID exactly once");
  }
}

function normalizeOmm(value: unknown): OmmRecord {
  const raw = objectValue(value);
  const id = noradId(raw.NORAD_CAT_ID);
  if (!id || !isoUtc(raw.EPOCH) || typeof raw.OBJECT_NAME !== "string") {
    throw new Error("OMM is missing a valid ID, name, or epoch");
  }
  for (const field of REQUIRED_OMM_NUMBERS) {
    if (numeric(raw[field]) === null) throw new Error(`OMM ${id} has invalid ${field}`);
  }
  for (const field of OPTIONAL_OMM_NUMBERS) {
    if (raw[field] !== null && raw[field] !== undefined && numeric(raw[field]) === null) {
      throw new Error(`OMM ${id} has invalid ${field}`);
    }
  }
  const omm: Record<string, OmmValue> = {};
  for (const [key, field] of Object.entries(raw)) {
    if (field === null || typeof field === "string" || typeof field === "boolean" || numeric(field) !== null) {
      omm[key] = field as OmmValue;
    } else {
      throw new Error(`OMM ${id} has a non-scalar ${key}`);
    }
  }
  omm.NORAD_CAT_ID = id;
  return omm as OmmRecord;
}

function transmitterStatus(raw: SatnogsTransmitter): TransmitterStatus {
  if (raw.status === "invalid") return "invalid";
  if (raw.status !== "active" || raw.alive === false) return "inactive";
  return raw.alive === true && raw.unconfirmed !== true ? "active" : "uncertain";
}

function normalizeTransmitter(raw: SatnogsTransmitter, id: string): CatalogTransmitter | null {
  const transmitterId = typeof raw.uuid === "string" && /^[a-zA-Z0-9]{12,40}$/.test(raw.uuid) ? raw.uuid : null;
  const low = numeric(raw.downlink_low);
  const high = raw.downlink_high === null || raw.downlink_high === undefined ? low : numeric(raw.downlink_high);
  if (!transmitterId || low === null || high === null || low < 1 || high < low || high > 100_000_000_000) {
    return null;
  }
  const frequencyHz = Math.round((low + high) / 2);
  const sourceBandwidth = Math.round(high - low);
  const mode = typeof raw.mode === "string" ? raw.mode.toUpperCase() : "UNKNOWN";
  const baud = numeric(raw.baud);
  const status = transmitterStatus(raw);
  const profile = status === "active" ? curatedProfileFor(id, transmitterId, frequencyHz, mode, baud) : undefined;
  const evidenceUrls = profile ? [...profile.evidenceUrls] : [];
  return {
    id: transmitterId,
    description: typeof raw.description === "string" ? raw.description : "Downlink",
    frequencyHz,
    bandwidthHz: profile?.bandwidthHz ?? sourceBandwidth,
    mode,
    baud,
    status,
    verifiedAt: profile ? isoUtc(raw.updated) : null,
    decoderId: profile?.decoderId ?? null,
    captureRateSps: profile?.captureRateSps ?? null,
    antenna: profile?.antenna ?? null,
    policy: profile?.policy ?? "restricted",
    evidenceUrls,
  };
}

export function buildCatalog(
  ommInput: unknown,
  satelliteInput: unknown,
  transmitterInput: unknown,
  sequence: number,
  generatedAt: string,
  orbitalSource: "celestrak" | "space-track" = "celestrak",
): CatalogManifestV1 {
  if (!Array.isArray(ommInput) || !Array.isArray(satelliteInput) || !Array.isArray(transmitterInput)) {
    throw new Error("Upstream data must contain three JSON arrays");
  }
  if (!Number.isSafeInteger(sequence) || sequence < 1 || !isoUtc(generatedAt)) {
    throw new Error("Invalid catalog sequence or generation time");
  }

  const satellitesByNorad = new Map<string, SatnogsSatellite>();
  for (const item of satelliteInput) {
    const satellite = objectValue(item) as unknown as SatnogsSatellite;
    const id = noradId(satellite.norad_cat_id);
    if (id) satellitesByNorad.set(id, satellite);
  }
  const transmittersByNorad = new Map<string, SatnogsTransmitter[]>();
  for (const item of transmitterInput) {
    const transmitter = objectValue(item) as unknown as SatnogsTransmitter;
    const id = noradId(transmitter.norad_cat_id);
    if (!id) continue;
    const list = transmittersByNorad.get(id) ?? [];
    list.push(transmitter);
    transmittersByNorad.set(id, list);
  }

  // CelesTrak groups overlap, and a GP source can contain repeated IDs. Keep
  // the newest published elements for each NORAD ID.
  const orbitByNorad = new Map<string, OmmRecord>();
  for (const item of ommInput) {
    const omm = normalizeOmm(item);
    const previous = orbitByNorad.get(omm.NORAD_CAT_ID);
    if (!previous && orbitByNorad.size >= ANDROID_MAX_SATELLITES) {
      throw new Error(`Catalog exceeds Android satellite count limit (${ANDROID_MAX_SATELLITES}); no source IDs were dropped`);
    }
    if (!previous || Date.parse(isoUtc(omm.EPOCH)!) > Date.parse(isoUtc(previous.EPOCH)!)) {
      orbitByNorad.set(omm.NORAD_CAT_ID, omm);
    }
  }
  const satellites: CatalogSatellite[] = [];
  for (const omm of orbitByNorad.values()) {
    const id = omm.NORAD_CAT_ID;
    const satnogs = satellitesByNorad.get(id);
    const name = typeof satnogs?.name === "string" && satnogs.name.trim()
      ? satnogs.name.trim()
      : String(omm.OBJECT_NAME).trim();
    const aliases = new Set<string>();
    if (typeof satnogs?.names === "string") {
      for (const alias of satnogs.names.split(/[,;]/)) {
        if (alias.trim() && alias.trim().toLowerCase() !== name.toLowerCase()) aliases.add(alias.trim());
      }
    }
    if (typeof omm.OBJECT_NAME === "string" && omm.OBJECT_NAME.toLowerCase() !== name.toLowerCase()) {
      aliases.add(omm.OBJECT_NAME);
    }
    const transmitters = (transmittersByNorad.get(id) ?? [])
      .map((transmitter) => normalizeTransmitter(transmitter, id))
      .filter((transmitter): transmitter is CatalogTransmitter => transmitter !== null)
      .sort((a, b) => a.frequencyHz - b.frequencyHz || a.id.localeCompare(b.id));
    satellites.push({ noradId: id, name, aliases: [...aliases].sort(), omm, transmitters });
  }
  if (satellites.length === 0) throw new Error("Upstream returned no usable OMM records");
  if (orbitalSource === "space-track") assertSpaceTrackCatalogCoverage(ommInput, satellites);
  satellites.sort((a, b) => Number(a.noradId) - Number(b.noradId));
  return {
    schemaVersion: 1,
    sequence,
    generatedAt: isoUtc(generatedAt)!,
    // This is the successful upstream refresh time. An individual orbit's
    // EPOCH can be in the future, so max(EPOCH) is not a valid freshness clock.
    // The Android receiver checks the chosen orbit's epoch separately.
    sourceUpdatedAt: isoUtc(generatedAt)!,
    satellites,
    attribution: [
      orbitalSource === "space-track"
        ? "Orbital elements: USSPACECOM 18 SDS via Space-Track.org (https://www.space-track.org/); supplied GP records retained without epoch or decay filtering. Source-ID retention does not independently prove upstream completeness."
        : "Orbital elements: CelesTrak (https://celestrak.org/).",
      "Satellite and transmitter metadata: SatNOGS DB, CC BY-SA 4.0 (https://db.satnogs.org/).",
      "Decoder eligibility: OrbitScope curated public-signal profiles.",
    ],
  };
}
