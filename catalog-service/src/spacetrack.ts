// Space-Track's public login form currently POSTs these fields to /auth/login.
// Keep this integration optional until it has been exercised with an approved
// account; a changed form or response fails closed without publishing a catalog.
const LOGIN_URL = "https://www.space-track.org/auth/login";
// GP already supplies the newest published set per object. Do not apply the
// optional on-orbit/recent-epoch filter: old and decayed latest records remain
// available for tracking history, with Android handling their explicit status.
export const SPACETRACK_GP_URL =
  "https://www.space-track.org/basicspacedata/query/class/gp/format/json";

export interface SpaceTrackCredentials {
  identity: string;
  password: string;
}

const CORE_OMM_FIELDS = [
  "MEAN_MOTION", "ECCENTRICITY", "INCLINATION", "RA_OF_ASC_NODE",
  "ARG_OF_PERICENTER", "MEAN_ANOMALY",
] as const;
const OPTIONAL_NUMERIC_OMM_FIELDS = [
  "BSTAR", "MEAN_MOTION_DOT",
  "MEAN_MOTION_DDOT", "EPHEMERIS_TYPE", "ELEMENT_SET_NO", "REV_AT_EPOCH",
] as const;

// The GP API also returns duplicate TLE text, database fields, and OMM
// metadata the Android propagator does not use. Keep tracking fields compact
// so a broad public GP result has a chance to fit the client's size limit.
const OPTIONAL_TEXT_OMM_FIELDS = ["OBJECT_ID", "CLASSIFICATION_TYPE", "OBJECT_TYPE"] as const;
const EXPECTED_OMM_CONTEXT = {
  CENTER_NAME: "EARTH",
  REF_FRAME: "TEME",
  TIME_SYSTEM: "UTC",
  MEAN_ELEMENT_THEORY: "SGP4",
} as const;

function htmlAttribute(tag: string, name: string): string | null {
  const match = new RegExp(`(?:^|\\s)${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, "i").exec(tag);
  return match ? (match[1] ?? match[2] ?? match[3]) : null;
}

function csrfToken(html: string): string {
  for (const input of html.match(/<input\b[^>]*>/gi) ?? []) {
    if (htmlAttribute(input, "name") === "spacetrack_csrf_token") {
      const token = htmlAttribute(input, "value");
      if (token && /^[A-Za-z0-9_-]{16,128}$/.test(token)) return token;
    }
  }
  throw new Error("Space-Track login form is missing its CSRF token");
}

function addCookies(jar: Map<string, string>, headers: Headers): void {
  for (const header of headers.getSetCookie()) {
    const pair = header.split(";", 1)[0];
    const separator = pair.indexOf("=");
    if (separator < 1) continue;
    const name = pair.slice(0, separator).trim();
    const value = pair.slice(separator + 1).trim();
    if (!/^[A-Za-z0-9_!#$%&'*+.^`|~-]+$/.test(name)) continue;
    if (value) jar.set(name, value);
    else jar.delete(name);
  }
}

function cookieHeader(jar: Map<string, string>): string {
  return [...jar].map(([name, value]) => `${name}=${value}`).join("; ");
}

async function request(fetcher: typeof fetch, url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetcher(url, { ...init, redirect: "manual", signal: AbortSignal.timeout(120_000) });
  } catch {
    // Fetch failures must not put credentials, cookies, or response bodies in
    // the persistent refresh-attempt record.
    throw new Error("Space-Track request failed");
  }
}

function numeric(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string" || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim())) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function utcEpoch(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().replace(" ", "T");
  const dated = new Date(/(?:Z|[+-]\d\d:\d\d)$/i.test(normalized) ? normalized : `${normalized}Z`);
  return Number.isFinite(dated.valueOf()) ? dated.toISOString() : null;
}

function decayDate(value: unknown): string | undefined {
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value !== "string") throw new Error("Space-Track GP contained an invalid decay date");
  const date = value.trim();
  if (!date) return undefined;
  if (!/^\d{4}-\d\d-\d\d$/.test(date) ||
      !Number.isFinite(Date.parse(`${date}T00:00:00Z`)) ||
      new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) !== date) {
    throw new Error("Space-Track GP contained an invalid decay date");
  }
  return date;
}

/** Compact and validate one raw Space-Track GP JSON row for the Android catalog. */
export function normalizeSpaceTrackOmm(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Space-Track GP contained an invalid OMM record");
  }
  const raw = value as Record<string, unknown>;
  for (const [field, expected] of Object.entries(EXPECTED_OMM_CONTEXT)) {
    const actual = raw[field];
    if (actual !== undefined && actual !== null &&
      (typeof actual !== "string" || actual.trim().toUpperCase() !== expected)) {
      throw new Error("Space-Track GP contained unsupported orbital coordinates or theory");
    }
  }
  const id = raw.NORAD_CAT_ID;
  if (!((typeof id === "number" && Number.isSafeInteger(id) && id > 0 && id <= 999_999_999) ||
    (typeof id === "string" && /^\d{1,9}$/.test(id) && Number(id) > 0))) {
    throw new Error("Space-Track GP contained an invalid NORAD ID");
  }
  const omm: Record<string, unknown> = { NORAD_CAT_ID: id };
  for (const field of CORE_OMM_FIELDS) {
    const parsed = numeric(raw[field]);
    if (parsed === null) throw new Error("Space-Track GP contained incomplete orbital elements");
    omm[field] = parsed;
  }
  const meanMotion = omm.MEAN_MOTION as number;
  const eccentricity = omm.ECCENTRICITY as number;
  const inclination = omm.INCLINATION as number;
  if (meanMotion <= 0 || eccentricity < 0 || eccentricity >= 1 || inclination < 0 || inclination > 180) {
    throw new Error("Space-Track GP contained invalid orbital elements");
  }
  // Android defaults absent drag terms to zero. Preserve measured values when
  // present; omit null or invalid optional fields rather than signing them.
  for (const field of OPTIONAL_NUMERIC_OMM_FIELDS) {
    const parsed = numeric(raw[field]);
    if (parsed !== null) omm[field] = parsed;
  }
  for (const field of OPTIONAL_TEXT_OMM_FIELDS) {
    const value = raw[field];
    if (typeof value === "string" && value.trim()) omm[field] = value.trim();
  }
  const epoch = utcEpoch(raw.EPOCH);
  if (!epoch) throw new Error("Space-Track GP contained an invalid orbital epoch");
  omm.EPOCH = epoch;
  const decay = decayDate(raw.DECAY_DATE);
  if (decay !== undefined) omm.DECAY_DATE = decay;
  omm.OBJECT_NAME = typeof raw.OBJECT_NAME === "string" && raw.OBJECT_NAME.trim()
    ? raw.OBJECT_NAME.trim() : `NORAD ${id}`;
  return omm;
}

/** Authenticate once and return the single GP response without consuming its body. */
export async function openSpaceTrackGpResponse(
  credentials: SpaceTrackCredentials,
  fetcher: typeof fetch = fetch,
): Promise<Response> {
  if (!credentials.identity.trim() || !credentials.password) {
    throw new Error("Space-Track credentials are incomplete");
  }
  const cookies = new Map<string, string>();
  const loginPage = await request(fetcher, LOGIN_URL, { headers: { Accept: "text/html" } });
  if (loginPage.status !== 200 || !loginPage.headers.get("Content-Type")?.toLowerCase().includes("text/html")) {
    throw new Error(`Space-Track login form returned HTTP ${loginPage.status}`);
  }
  addCookies(cookies, loginPage.headers);
  if (cookies.size === 0) throw new Error("Space-Track login form did not provide a session cookie");
  const token = csrfToken(await loginPage.text());
  const body = new URLSearchParams({
    spacetrack_csrf_token: token,
    identity: credentials.identity.trim(),
    password: credentials.password,
  });
  const login = await request(fetcher, LOGIN_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Cookie: cookieHeader(cookies),
    },
    body,
  });
  if (login.status !== 200 && login.status !== 302 && login.status !== 303) {
    throw new Error(`Space-Track login failed with HTTP ${login.status}`);
  }
  const location = login.headers.get("Location");
  if (location) {
    const target = new URL(location, LOGIN_URL);
    if (target.origin !== new URL(LOGIN_URL).origin || target.pathname === "/auth/login") {
      throw new Error("Space-Track login did not establish an authenticated session");
    }
  }
  if (login.status === 200 && login.headers.get("Content-Type")?.toLowerCase().includes("text/html")) {
    const html = await login.text();
    if (html.includes("spacetrack_csrf_token") && html.includes("name=\"identity\"")) {
      throw new Error("Space-Track login did not establish an authenticated session");
    }
  }
  addCookies(cookies, login.headers);
  if (cookies.size === 0) throw new Error("Space-Track login did not provide a session cookie");
  const gp = await request(fetcher, SPACETRACK_GP_URL, {
    headers: { Accept: "application/json", Cookie: cookieHeader(cookies) },
  });
  if (gp.status !== 200) throw new Error(`Space-Track GP returned HTTP ${gp.status}`);
  if (!gp.headers.get("Content-Type")?.toLowerCase().includes("json")) {
    throw new Error("Space-Track GP did not return JSON");
  }
  return gp;
}

export async function fetchSpaceTrackGp(
  credentials: SpaceTrackCredentials,
  fetcher: typeof fetch = fetch,
  minimumRows = 1_000,
): Promise<unknown[]> {
  const gp = await openSpaceTrackGpResponse(credentials, fetcher);
  let data: unknown;
  try {
    data = await gp.json();
  } catch {
    throw new Error("Space-Track GP JSON could not be parsed");
  }
  if (!Array.isArray(data) || data.length < minimumRows) {
    throw new Error("Space-Track GP dataset is unexpectedly small or invalid");
  }
  return data.map(normalizeSpaceTrackOmm);
}
