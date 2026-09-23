import assert from "node:assert/strict";
import test from "node:test";
import { buildCatalog } from "../src/catalog.ts";
import { upstreamRows } from "./fixtures.ts";

test("normalizes real OMM examples and enables only exact curated transmitter matches", () => {
  const rows = upstreamRows();
  const catalog = buildCatalog(rows.omm, rows.satellites, rows.transmitters, 2, "2026-09-23T12:00:00Z");
  assert.deepEqual(catalog.satellites.map((sat) => sat.noradId), ["25544", "40069", "59051"]);
  assert.deepEqual(catalog.satellites.map((sat) => sat.transmitters.map((tx) => tx.decoderId)), [
    ["AUDIO_NFM", "AX25_AFSK1200"], ["METEOR_LRPT_72K"], ["METEOR_LRPT_80K"],
  ]);
  assert.equal(catalog.satellites[0]?.omm.NORAD_CAT_ID, "25544");
  assert.equal(catalog.sourceUpdatedAt, "2026-09-22T23:00:11.712Z");

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
