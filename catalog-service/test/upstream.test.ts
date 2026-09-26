import assert from "node:assert/strict";
import test from "node:test";
import { CELESTRAK_OMM_URLS, fetchUpstream } from "../src/upstream.ts";

test("loads each configured CelesTrak GP query once and retains tracking-only records", async () => {
  const requests: string[] = [];
  const fetcher = (async (input: RequestInfo | URL) => {
    const url = String(input);
    requests.push(url);
    if (url.includes("celestrak.org")) return Response.json([{ NORAD_CAT_ID: requests.length }]);
    return Response.json([]);
  }) as typeof fetch;

  const result = await fetchUpstream(fetcher, 1);
  assert.deepEqual(requests.slice(0, CELESTRAK_OMM_URLS.length), CELESTRAK_OMM_URLS);
  assert.deepEqual(CELESTRAK_OMM_URLS.slice(-3), [
    "https://celestrak.org/NORAD/elements/gp.php?NAME=DEB&FORMAT=JSON",
    "https://celestrak.org/NORAD/elements/gp.php?NAME=R%2FB&FORMAT=JSON",
    "https://celestrak.org/NORAD/elements/gp.php?NAME=COOLANT&FORMAT=JSON",
  ]);
  assert.equal(new Set(CELESTRAK_OMM_URLS).size, CELESTRAK_OMM_URLS.length);
  assert.equal(result.omm.length, CELESTRAK_OMM_URLS.length);
  assert.deepEqual(requests.slice(CELESTRAK_OMM_URLS.length).map((url) => new URL(url).host), [
    "db.satnogs.org", "db.satnogs.org",
  ]);
});

test("stops CelesTrak requests on a redirect or server error", async () => {
  for (const status of [301, 503]) {
    const requests: string[] = [];
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push(String(input));
      assert.equal(init?.redirect, "manual");
      if (requests.length === 2) return new Response(null, { status });
      return Response.json([{ NORAD_CAT_ID: 1 }]);
    }) as typeof fetch;
    await assert.rejects(fetchUpstream(fetcher, 1), new RegExp(`HTTP ${status}`));
    assert.deepEqual(requests, CELESTRAK_OMM_URLS.slice(0, 2));
  }
});
