const CELESTRAK_URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=SATNOGS&FORMAT=JSON";
const SATNOGS_SATELLITES_URL = "https://db.satnogs.org/api/satellites/";
const SATNOGS_TRANSMITTERS_URL = "https://db.satnogs.org/api/transmitters/";

async function fetchArray(url: string, fetcher: typeof fetch): Promise<unknown[]> {
  const response = await fetcher(url, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error(`Upstream ${new URL(url).host} returned HTTP ${response.status}`);
  const data: unknown = await response.json();
  if (!Array.isArray(data)) throw new Error(`Upstream ${new URL(url).host} did not return a JSON array`);
  return data;
}

export async function fetchUpstream(fetcher: typeof fetch = fetch): Promise<{
  omm: unknown[];
  satellites: unknown[];
  transmitters: unknown[];
}> {
  const [omm, satellites, transmitters] = await Promise.all([
    fetchArray(CELESTRAK_URL, fetcher),
    fetchArray(SATNOGS_SATELLITES_URL, fetcher),
    fetchArray(SATNOGS_TRANSMITTERS_URL, fetcher),
  ]);
  return { omm, satellites, transmitters };
}
