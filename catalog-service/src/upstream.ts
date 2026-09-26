import { fetchSpaceTrackGp, type SpaceTrackCredentials } from "./spacetrack.ts";

// These are documented groups and NAME substring queries from CelesTrak's GP
// query interface, not an exhaustive public GP catalog. Fetch in order and
// stop on the first error so a bad response never causes more automated
// requests to CelesTrak.
export const CELESTRAK_OMM_URLS = [
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=ACTIVE&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=SATNOGS&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=LAST-30-DAYS&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=ANALYST&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=FENGYUN-1C-DEBRIS&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=IRIDIUM-33-DEBRIS&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?GROUP=COSMOS-2251-DEBRIS&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?SPECIAL=GPZ-PLUS&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?NAME=DEB&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?NAME=R%2FB&FORMAT=JSON",
  "https://celestrak.org/NORAD/elements/gp.php?NAME=COOLANT&FORMAT=JSON",
] as const;
const SATNOGS_SATELLITES_URL = "https://db.satnogs.org/api/satellites/";
const SATNOGS_TRANSMITTERS_URL = "https://db.satnogs.org/api/transmitters/";

async function fetchArray(url: string, fetcher: typeof fetch): Promise<unknown[]> {
  const response = await fetcher(url, {
    headers: { Accept: "application/json" },
    redirect: "manual",
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error(`Upstream ${new URL(url).host} returned HTTP ${response.status}`);
  const data: unknown = await response.json();
  if (!Array.isArray(data)) throw new Error(`Upstream ${new URL(url).host} did not return a JSON array`);
  return data;
}

export async function fetchUpstream(
  fetcher: typeof fetch = fetch,
  minimumRows = 1_000,
  spaceTrack?: SpaceTrackCredentials,
): Promise<{
  omm: unknown[];
  satellites: unknown[];
  transmitters: unknown[];
  source: "celestrak" | "space-track";
}> {
  let omm: unknown[];
  if (spaceTrack) {
    omm = await fetchSpaceTrackGp(spaceTrack, fetcher, minimumRows);
  } else {
    omm = [];
    for (const [index, url] of CELESTRAK_OMM_URLS.entries()) {
      const group = await fetchArray(url, fetcher);
      if (index === 0 && group.length < minimumRows) {
        throw new Error("CelesTrak active orbit dataset is unexpectedly small");
      }
      omm.push(...group);
    }
  }
  const [satellites, transmitters] = await Promise.all([
    fetchArray(SATNOGS_SATELLITES_URL, fetcher),
    fetchArray(SATNOGS_TRANSMITTERS_URL, fetcher),
  ]);
  return { omm, satellites, transmitters, source: spaceTrack ? "space-track" : "celestrak" };
}
