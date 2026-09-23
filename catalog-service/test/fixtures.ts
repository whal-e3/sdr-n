import { readFileSync } from "node:fs";
import type { CatalogManifestV1 } from "../src/types.ts";

const demo = JSON.parse(readFileSync(new URL("../fixtures/demo-catalog.json", import.meta.url), "utf8")) as CatalogManifestV1;

export function upstreamRows() {
  return {
    omm: demo.satellites.map((sat) => ({ ...sat.omm, NORAD_CAT_ID: Number(sat.noradId) })),
    satellites: demo.satellites.map((sat) => ({
      norad_cat_id: Number(sat.noradId),
      sat_id: `SAT-${sat.noradId}`,
      name: sat.name,
      names: sat.aliases.join(", "),
    })),
    transmitters: demo.satellites.flatMap((sat) => sat.transmitters.map((tx) => ({
      uuid: tx.id,
      norad_cat_id: Number(sat.noradId),
      downlink_low: tx.frequencyHz,
      downlink_high: tx.frequencyHz,
      mode: tx.mode,
      baud: tx.baud,
      status: "active",
      alive: true,
      unconfirmed: false,
      updated: tx.verifiedAt,
      description: tx.description,
    }))),
  };
}
