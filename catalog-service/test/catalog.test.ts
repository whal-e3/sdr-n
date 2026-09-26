import assert from "node:assert/strict";
import test from "node:test";
import { ANDROID_MAX_SATELLITES, assertSpaceTrackCatalogCoverage, buildCatalog } from "../src/catalog.ts";
import { upstreamRows } from "./fixtures.ts";

test("normalizes real OMM examples and enables only exact curated transmitter matches", () => {
  const rows = upstreamRows();
  const catalog = buildCatalog(rows.omm, rows.satellites, rows.transmitters, 2, "2026-09-23T12:00:00Z");
  assert.deepEqual(catalog.satellites.map((sat) => sat.noradId), ["25544", "40069", "59051"]);
  assert.deepEqual(catalog.satellites.map((sat) => sat.transmitters.map((tx) => tx.decoderId)), [
    ["AUDIO_NFM", "AX25_AFSK1200"], ["METEOR_LRPT_72K"], ["METEOR_LRPT_80K"],
  ]);
  assert.equal(catalog.satellites[0]?.omm.NORAD_CAT_ID, "25544");
  assert.equal(catalog.sourceUpdatedAt, "2026-09-23T12:00:00.000Z");

  rows.transmitters[0]!.mode = "FSK";
  const changed = buildCatalog(rows.omm, rows.satellites, rows.transmitters, 3, "2026-09-23T12:00:00Z");
  assert.equal(changed.satellites[0]?.transmitters[0]?.decoderId, null);
  assert.equal(changed.satellites[0]?.transmitters[0]?.policy, "restricted");
});

test("keeps 9-digit NORAD IDs as strings and fails a malformed OMM update", () => {
  const rows = upstreamRows();
  rows.omm = [{ ...rows.omm[0], NORAD_CAT_ID: 799_123_456 }];
  const catalog = buildCatalog(rows.omm, [], [], 1, "2026-09-23T12:00:00Z");
  assert.equal(catalog.satellites[0]?.noradId, "799123456");
  assert.deepEqual(catalog.satellites[0]?.transmitters, []);
  assert.throws(() => buildCatalog([{ ...rows.omm[0], MEAN_MOTION: null }], [], [], 1, "2026-09-23T12:00:00Z"));
});

test("tracks OMM records with six core numbers and nullable optional fields", () => {
  const original = upstreamRows().omm[0]!;
  const sparse: Record<string, unknown> = { ...original };
  for (const field of [
    "BSTAR", "MEAN_MOTION_DOT", "MEAN_MOTION_DDOT",
    "EPHEMERIS_TYPE", "ELEMENT_SET_NO", "REV_AT_EPOCH",
  ]) delete sparse[field];
  sparse.EPHEMERIS_TYPE = null;
  const catalog = buildCatalog([sparse], [], [], 1, "2026-09-23T12:00:00Z");
  assert.equal(catalog.satellites.length, 1);
  assert.equal(catalog.satellites[0]?.omm.MEAN_MOTION, sparse.MEAN_MOTION);
  assert.equal(catalog.satellites[0]?.omm.BSTAR, undefined);
  assert.equal(catalog.satellites[0]?.omm.EPHEMERIS_TYPE, null);
  assert.throws(
    () => buildCatalog([{ ...sparse, BSTAR: "invalid" }], [], [], 1, "2026-09-23T12:00:00Z"),
    /invalid BSTAR/,
  );
});

test("merges overlapping orbit groups and retains satellites without downlinks", () => {
  const rows = upstreamRows();
  const older = { ...rows.omm[0], EPOCH: "2026-09-20T00:00:00Z", OBJECT_NAME: "OLDER ISS" };
  const newer = { ...rows.omm[0], EPOCH: "2026-09-23T00:00:00Z", OBJECT_NAME: "CURRENT ISS" };
  const other = { ...rows.omm[1], NORAD_CAT_ID: 123_456, OBJECT_NAME: "ACTIVE SATELLITE" };
  const catalog = buildCatalog([older, other, newer], [], [], 1, "2026-09-23T12:00:00Z");
  assert.equal(catalog.satellites.length, 2);
  assert.equal(catalog.satellites.find((sat) => sat.noradId === "25544")?.omm.EPOCH, newer.EPOCH);
  assert.deepEqual(catalog.satellites.find((sat) => sat.noradId === "123456")?.transmitters, []);
});

test("future-dated orbital epochs cannot move the catalog freshness clock forward", () => {
  const rows = upstreamRows();
  const futureOrbit = { ...rows.omm[0], EPOCH: "2026-09-27T20:31:07.671360" };
  const catalog = buildCatalog([futureOrbit], [], [], 1, "2026-09-24T12:00:00Z");
  assert.equal(catalog.sourceUpdatedAt, "2026-09-24T12:00:00.000Z");
  assert.equal(catalog.satellites[0]?.omm.EPOCH, futureOrbit.EPOCH);
});

test("latest-GP source identity checks reject omissions even when the orbit count is unchanged", () => {
  const rows = [
    { NORAD_CAT_ID: 25544 },
    { NORAD_CAT_ID: "025544" },
    { NORAD_CAT_ID: "123456" },
  ];
  assertSpaceTrackCatalogCoverage(rows, [{ noradId: "25544" }, { noradId: "123456" }]);
  for (const actual of [
    [{ noradId: "25544" }],
    [{ noradId: "25544" }, { noradId: "999999" }],
    [{ noradId: "25544" }, { noradId: "123456" }, { noradId: "123456" }],
  ]) assert.throws(() => assertSpaceTrackCatalogCoverage(rows, actual), /every distinct source ID exactly once/);
});

test("both source modes reject more than Android's record ceiling before signing rather than truncate", () => {
  const original = upstreamRows().omm[0]!;
  const rows = Array.from({ length: ANDROID_MAX_SATELLITES + 1 }, (_, index) => ({
    ...original, NORAD_CAT_ID: 100_000_000 + index,
  }));
  for (const source of ["celestrak", "space-track"] as const) {
    assert.throws(() => buildCatalog(rows, [], [], 1, "2026-09-26T12:00:00Z", source),
      /Android satellite count limit \(100000\); no source IDs were dropped/);
  }
});
