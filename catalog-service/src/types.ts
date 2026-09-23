export type DecoderId =
  | "AUDIO_NFM"
  | "AX25_AFSK1200"
  | "METEOR_LRPT_72K"
  | "METEOR_LRPT_80K"
  | "CHANNEL_IQ";

export type ReceptionPolicy = "public" | "amateur" | "restricted";
export type TransmitterStatus = "active" | "uncertain" | "inactive" | "invalid";
export type OmmValue = string | number | boolean | null;
export type OmmRecord = Record<string, OmmValue> & { NORAD_CAT_ID: string };

export interface CatalogTransmitter {
  id: string;
  description: string;
  frequencyHz: number;
  bandwidthHz: number;
  mode: string;
  baud: number | null;
  status: TransmitterStatus;
  verifiedAt: string | null;
  decoderId: DecoderId | null;
  captureRateSps: number | null;
  antenna: { band: string; description: string } | null;
  policy: ReceptionPolicy;
  evidenceUrls: string[];
}

export interface CatalogSatellite {
  noradId: string;
  name: string;
  aliases: string[];
  omm: OmmRecord;
  transmitters: CatalogTransmitter[];
}

export interface CatalogManifestV1 {
  schemaVersion: 1;
  sequence: number;
  generatedAt: string;
  sourceUpdatedAt: string;
  satellites: CatalogSatellite[];
  attribution: string[];
}

export interface SatnogsSatellite {
  sat_id: unknown;
  norad_cat_id: unknown;
  name: unknown;
  names?: unknown;
  status?: unknown;
}

export interface SatnogsTransmitter {
  uuid: unknown;
  norad_cat_id: unknown;
  sat_id?: unknown;
  description?: unknown;
  downlink_low: unknown;
  downlink_high?: unknown;
  mode?: unknown;
  baud?: unknown;
  status?: unknown;
  alive?: unknown;
  unconfirmed?: unknown;
  updated?: unknown;
}

export interface CatalogSignature {
  schemaVersion: 1;
  sequence: number;
  algorithm: "Ed25519";
  keyId: string;
  sha256: string;
  signature: string;
}

export interface CatalogPointer {
  sequence: number;
  generatedAt: string;
  sourceUpdatedAt: string;
  satelliteCount: number;
  transmitterCount: number;
  sha256: string;
  keyId: string;
}

export interface AttemptState {
  attemptedAt: string;
  completedAt?: string;
  outcome?: "published" | "failed";
  error?: string;
}

export interface Env {
  CATALOG_BUCKET: R2Bucket;
  CATALOG_SIGNING_KEY_PKCS8_BASE64: string;
  SIGNING_KEY_ID: string;
}
