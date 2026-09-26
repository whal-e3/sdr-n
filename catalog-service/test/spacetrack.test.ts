import assert from "node:assert/strict";
import test from "node:test";
import { buildCatalog } from "../src/catalog.ts";
import { fetchSpaceTrackGp, SPACETRACK_GP_URL } from "../src/spacetrack.ts";
import { fetchUpstream } from "../src/upstream.ts";
import { upstreamRows } from "./fixtures.ts";

const credentials = { identity: "tester@example.org", password: "test-secret" };
const loginHtml = '<form action="/auth/login"><input name="spacetrack_csrf_token" value="0123456789abcdef0123456789abcdef"><input name="identity"></form>';

function loginPage(): Response {
  const headers = new Headers({ "Content-Type": "text/html; charset=utf-8" });
  headers.append("Set-Cookie", "spacetrack_csrf_cookie=first; Path=/; HttpOnly");
  headers.append("Set-Cookie", "chocolatechip=anonymous; Path=/; HttpOnly");
  return new Response(loginHtml, { headers });
}

function authenticated(): Response {
  return new Response(null, {
    status: 302,
    headers: { Location: "/", "Set-Cookie": "chocolatechip=authenticated; Path=/; HttpOnly" },
  });
}

function fetcherForGp(rows: unknown[]): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/auth/login") && init?.method !== "POST") return loginPage();
    if (url.endsWith("/auth/login")) return authenticated();
    if (url === SPACETRACK_GP_URL) return Response.json(rows);
    throw new Error(`Unexpected URL ${url}`);
  }) as typeof fetch;
}

test("authenticates with CSRF cookies and loads one unfiltered latest GP JSON set", async () => {
  assert.equal(SPACETRACK_GP_URL, "https://www.space-track.org/basicspacedata/query/class/gp/format/json");
  const fixture = upstreamRows();
  const raw = {
    ...fixture.omm[0],
    OBJECT_NAME: null,
    EPOCH: "2026-09-23 12:00:00.123456",
    TLE_LINE1: "duplicate orbit text",
  } as Record<string, unknown>;
  for (const key of [
    "MEAN_MOTION", "ECCENTRICITY", "INCLINATION", "RA_OF_ASC_NODE",
    "ARG_OF_PERICENTER", "MEAN_ANOMALY", "BSTAR", "MEAN_MOTION_DOT",
    "MEAN_MOTION_DDOT", "EPHEMERIS_TYPE", "ELEMENT_SET_NO", "REV_AT_EPOCH",
  ]) raw[key] = String(raw[key]);
  const requests: string[] = [];
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(url);
    assert.equal(init?.redirect, "manual");
    if (url.endsWith("/auth/login") && init?.method !== "POST") return loginPage();
    if (url.endsWith("/auth/login")) {
      assert.equal(new URLSearchParams(init?.body as URLSearchParams).get("identity"), credentials.identity);
      assert.equal(new URLSearchParams(init?.body as URLSearchParams).get("password"), credentials.password);
      assert.equal(new URLSearchParams(init?.body as URLSearchParams).get("spacetrack_csrf_token"), "0123456789abcdef0123456789abcdef");
      assert.match(new Headers(init?.headers).get("Cookie") ?? "", /spacetrack_csrf_cookie=first/);
      assert.match(new Headers(init?.headers).get("Cookie") ?? "", /chocolatechip=anonymous/);
      return authenticated();
    }
    if (url === SPACETRACK_GP_URL) {
      const cookie = new Headers(init?.headers).get("Cookie") ?? "";
      assert.match(cookie, /spacetrack_csrf_cookie=first/);
      assert.match(cookie, /chocolatechip=authenticated/);
      assert.doesNotMatch(cookie, /chocolatechip=anonymous/);
      return Response.json([raw]);
    }
    if (url.includes("/satellites/")) return Response.json(fixture.satellites);
    if (url.includes("/transmitters/")) return Response.json(fixture.transmitters);
    throw new Error(`Unexpected URL ${url}`);
  }) as typeof fetch;

  const result = await fetchUpstream(fetcher, 1, credentials);
  assert.equal(result.source, "space-track");
  assert.deepEqual(requests.slice(0, 3), [
    "https://www.space-track.org/auth/login",
    "https://www.space-track.org/auth/login",
    SPACETRACK_GP_URL,
  ]);
  const omm = result.omm[0] as Record<string, unknown>;
  assert.equal(omm.OBJECT_NAME, `NORAD ${raw.NORAD_CAT_ID}`);
  assert.equal(omm.EPOCH, "2026-09-23T12:00:00.123Z");
  assert.equal(typeof omm.MEAN_MOTION, "number");
  assert.equal(omm.TLE_LINE1, undefined);
});

test("unfiltered latest GP retains old epochs and validated decay dates without radio metadata", async () => {
  const original = upstreamRows().omm[0]!;
  const rows = [
    { ...original, NORAD_CAT_ID: 123456, EPOCH: "2025-03-01T12:00:00Z", DECAY_DATE: null },
    { ...original, NORAD_CAT_ID: 123457, EPOCH: "2024-01-01T12:00:00Z", DECAY_DATE: "2024-01-02" },
  ];
  const omm = await fetchSpaceTrackGp(credentials, fetcherForGp(rows), 1);
  const catalog = buildCatalog(omm, [], [], 1, "2026-09-26T12:00:00Z", "space-track");
  assert.deepEqual(catalog.satellites.map((row) => row.noradId), ["123456", "123457"]);
  assert.equal(catalog.satellites[0]?.omm.EPOCH, "2025-03-01T12:00:00.000Z");
  assert.equal(catalog.satellites[0]?.omm.DECAY_DATE, undefined);
  assert.equal(catalog.satellites[1]?.omm.DECAY_DATE, "2024-01-02");
  assert.ok(catalog.satellites.every((row) => row.transmitters.length === 0));
});

test("decay metadata is omitted when absent and rejects nonempty malformed calendar dates", async () => {
  const original = upstreamRows().omm[0]!;
  for (const DECAY_DATE of [undefined, null, "", "   "]) {
    const omm = (await fetchSpaceTrackGp(credentials, fetcherForGp([{ ...original, DECAY_DATE }]), 1))[0] as Record<string, unknown>;
    assert.equal(omm.DECAY_DATE, undefined);
  }
  for (const DECAY_DATE of ["2025-02-29", "2024-02-30", "2024-13-01", "2024-01-02T00:00:00Z", "unknown", 20240102]) {
    await assert.rejects(fetchSpaceTrackGp(credentials, fetcherForGp([{ ...original, DECAY_DATE }]), 1), /invalid decay date/);
  }
  const valid = (await fetchSpaceTrackGp(credentials, fetcherForGp([{ ...original, DECAY_DATE: " 2024-02-29 " }]), 1))[0] as Record<string, unknown>;
  assert.equal(valid.DECAY_DATE, "2024-02-29");
});

test("nullable GP metadata is omitted while all six core orbit numbers remain required", async () => {
  const fixture = upstreamRows();
  const sparse = {
    ...fixture.omm[0],
    OBJECT_NAME: null,
    OBJECT_ID: null,
    CLASSIFICATION_TYPE: null,
    OBJECT_TYPE: { unexpected: "object" },
    BSTAR: null,
    MEAN_MOTION_DOT: "not-a-number",
    MEAN_MOTION_DDOT: undefined,
    EPHEMERIS_TYPE: null,
    ELEMENT_SET_NO: "unknown",
    REV_AT_EPOCH: null,
  };
  const omm = (await fetchSpaceTrackGp(credentials, fetcherForGp([sparse]), 1))[0] as Record<string, unknown>;
  assert.equal(omm.OBJECT_NAME, `NORAD ${sparse.NORAD_CAT_ID}`);
  for (const field of [
    "OBJECT_ID", "CLASSIFICATION_TYPE", "OBJECT_TYPE",
    "BSTAR", "MEAN_MOTION_DOT", "MEAN_MOTION_DDOT",
    "EPHEMERIS_TYPE", "ELEMENT_SET_NO", "REV_AT_EPOCH",
  ]) assert.equal(field in omm, false, field);
  const catalog = buildCatalog([omm], [], [], 1, "2026-09-23T12:00:00Z", "space-track");
  assert.equal(catalog.satellites[0]?.noradId, String(sparse.NORAD_CAT_ID));

  for (const required of [
    "MEAN_MOTION", "ECCENTRICITY", "INCLINATION", "RA_OF_ASC_NODE",
    "ARG_OF_PERICENTER", "MEAN_ANOMALY",
  ]) {
    await assert.rejects(
      fetchSpaceTrackGp(credentials, fetcherForGp([{ ...sparse, [required]: null }]), 1),
      /incomplete orbital elements/,
      required,
    );
  }
  await assert.rejects(
    fetchSpaceTrackGp(credentials, fetcherForGp([{ ...sparse, MEAN_MOTION: 0 }]), 1),
    /invalid orbital elements/,
  );
});

test("rejects failed authentication and invalid GP responses without exposing secrets", async () => {
  for (const failure of ["missing-csrf", "login-403", "login-redirect", "gp-redirect", "gp-html", "gp-object", "gp-incomplete", "gp-frame", "network-error"]) {
    const fixture = upstreamRows();
    const requests: string[] = [];
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push(url);
      if (url.endsWith("/auth/login") && init?.method !== "POST") {
        return failure === "missing-csrf"
          ? new Response("<form></form>", { headers: loginPage().headers })
          : loginPage();
      }
      if (url.endsWith("/auth/login")) {
        if (failure === "login-403") return new Response("denied", { status: 403 });
        if (failure === "login-redirect") return new Response(null, { status: 302, headers: { Location: "/auth/login" } });
        return authenticated();
      }
      if (url === SPACETRACK_GP_URL) {
        if (failure === "network-error") throw new Error("test-secret in transport error");
        if (failure === "gp-redirect") return new Response(null, { status: 302, headers: { Location: "/auth/login" } });
        if (failure === "gp-html") return new Response("<html>login</html>", { headers: { "Content-Type": "text/html" } });
        if (failure === "gp-object") return Response.json({ error: "unauthorized" });
        if (failure === "gp-incomplete") return Response.json([{ ...fixture.omm[0], MEAN_MOTION: null }]);
        if (failure === "gp-frame") return Response.json([{ ...fixture.omm[0], REF_FRAME: "ITRF" }]);
      }
      throw new Error(`Unexpected URL ${url}`);
    }) as typeof fetch;
    await assert.rejects(fetchUpstream(fetcher, 1, credentials), (error: unknown) => {
      assert.doesNotMatch(String(error), /test-secret|tester@example\.org/);
      assert.match(String(error), /Space-Track/);
      return true;
    }, failure);
    assert.ok(requests.length <= 3, failure);
  }
});
