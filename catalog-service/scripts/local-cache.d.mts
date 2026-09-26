export interface SavedSource {
  url: string;
  file: string;
  kind: "omm" | "satellites" | "transmitters";
  fetchedAt: string;
  sha256: string;
  rows: number;
  distinctOrbits?: number;
}

export interface LocalCacheCommonOptions {
  outputDir: string;
  privateKeyPath: string;
  keyId: string;
  sequence: number;
  now?: Date;
  minimumRows?: number;
  minimumDistinctOrbits?: number;
  allowStale?: boolean;
  orbitUrls?: readonly string[];
}

export type LocalCacheOptions = LocalCacheCommonOptions & (
  | { sourceDir: string; spaceTrackGpPath?: never; satnogsSatellitesPath?: never;
      satnogsTransmittersPath?: never; gpDownloadedAt?: never;
      satnogsSatellitesDownloadedAt?: never; satnogsTransmittersDownloadedAt?: never;
      expectedMinimumOrbits?: number; previousCachePath?: string }
  | { sourceDir?: never; spaceTrackGpPath: string; satnogsSatellitesPath: string;
      satnogsTransmittersPath: string; gpDownloadedAt: string;
      satnogsSatellitesDownloadedAt: string; satnogsTransmittersDownloadedAt: string;
      expectedMinimumOrbits: number; previousCachePath?: string }
);

export interface UserFileSource {
  kind: "omm" | "satellites" | "transmitters";
  declaredSource: "Space-Track GP" | "SatNOGS DB";
  provenance: string;
  file: string;
  sha256: string;
  bytes: number;
  rows: number;
  downloadedAt: string;
}

export interface PreviousCacheSource {
  file: string;
  sha256: string;
  sequence: number;
  distinctOrbits: number;
}

export interface LocalCacheSummary {
  schemaVersion: 1;
  sequence: number;
  keyId: string;
  publicKeyBase64: string;
  generatedAt: string;
  sourceUpdatedAt: string;
  sourceExpiresAt: string;
  catalogSourceFreshAtBuild: boolean;
  orbitalSource: "celestrak" | "space-track";
  sourceTimeProvenance: string;
  sourceManifestSha256: string | null;
  sourceFiles: SavedSource[] | UserFileSource[];
  expectedMinimumOrbits: number | null;
  previousCache: PreviousCacheSource | null;
  requiredOrbits: number;
  rawOrbitRows: number;
  distinctOrbits: number;
  transmitterCount: number;
  plainBytes: number;
  gzipBytes: number;
  cacheBytes: number;
  gzipSha256: string;
}

export function fetchLocalSources(options: {
  sourceDir: string;
  stateDir?: string;
  fetcher?: (url: string, init?: RequestInit) => Promise<Response>;
  now?: () => Date;
  orbitUrls?: readonly string[];
}): Promise<{ schemaVersion: 1; orbitalSource: "celestrak"; sources: SavedSource[] }>;

export function fetchLocalSpaceTrackSources(options: {
  sourceDir: string;
  credentials: { identity: string; password: string };
  stateDir?: string;
  fetcher?: (url: string, init?: RequestInit) => Promise<Response>;
  now?: () => Date;
  minimumRows?: number;
  minimumDistinctOrbits?: number;
}): Promise<{
  schemaVersion: 1;
  orbitalSource: "space-track";
  provenance: string;
  sources: SavedSource[];
}>;

export function buildLocalCache(options: LocalCacheOptions): Promise<LocalCacheSummary>;
