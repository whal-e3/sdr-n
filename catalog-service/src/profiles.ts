import type { DecoderId, ReceptionPolicy } from "./types.ts";

interface CuratedProfile {
  noradId: string;
  transmitterId: string;
  frequencyHz: number;
  mode: string;
  baud: number | null;
  decoderId: DecoderId;
  captureRateSps: number;
  bandwidthHz: number;
  antenna: { band: string; description: string };
  policy: Exclude<ReceptionPolicy, "restricted">;
  evidenceUrls: string[];
}

// Exact UUID + RF-parameter matches prevent a crowdsourced mode change from
// silently enabling a decoder or changing the reception policy.
export const CURATED_PROFILES: readonly CuratedProfile[] = [
  {
    noradId: "25544",
    transmitterId: "PjfcFc4PZ8M8n3thuyA6x9",
    frequencyHz: 145_800_000,
    mode: "FM",
    baud: null,
    decoderId: "AUDIO_NFM",
    captureRateSps: 1_024_000,
    bandwidthHz: 25_000,
    antenna: { band: "2m", description: "VHF satellite antenna for 145.800 MHz" },
    policy: "amateur",
    evidenceUrls: ["https://www.ariss.org/contact-the-iss.html"],
  },
  {
    noradId: "25544",
    transmitterId: "ZJxCeQmih9zDfYNVrB4wRN",
    frequencyHz: 145_825_000,
    mode: "AFSK",
    baud: 1200,
    decoderId: "AX25_AFSK1200",
    captureRateSps: 1_024_000,
    bandwidthHz: 12_000,
    antenna: { band: "2m", description: "VHF satellite antenna for 145.825 MHz" },
    policy: "amateur",
    evidenceUrls: ["https://www.ariss.org/contact-the-iss.html"],
  },
  {
    noradId: "40069",
    transmitterId: "CojkGDaq3u42nRdLdfczng",
    frequencyHz: 137_100_000,
    mode: "LRPT",
    baud: 72_000,
    decoderId: "METEOR_LRPT_72K",
    captureRateSps: 2_400_000,
    bandwidthHz: 200_000,
    antenna: { band: "137MHz", description: "137 MHz weather-satellite V-dipole or QFH" },
    policy: "public",
    evidenceUrls: ["https://db.satnogs.org/satellite/40069/"],
  },
  {
    noradId: "59051",
    transmitterId: "CjvA8tYsAqC5f7jxV8D6T9",
    frequencyHz: 137_900_000,
    mode: "LRPT",
    baud: 80_000,
    decoderId: "METEOR_LRPT_80K",
    captureRateSps: 2_400_000,
    bandwidthHz: 220_000,
    antenna: { band: "137MHz", description: "137 MHz weather-satellite V-dipole or QFH" },
    policy: "public",
    evidenceUrls: ["https://db.satnogs.org/satellite/59051/"],
  },
];

export function curatedProfileFor(
  noradId: string,
  transmitterId: string,
  frequencyHz: number,
  mode: string,
  baud: number | null,
): CuratedProfile | undefined {
  return CURATED_PROFILES.find(
    (profile) =>
      profile.noradId === noradId &&
      profile.transmitterId === transmitterId &&
      profile.frequencyHz === frequencyHz &&
      profile.mode === mode.toUpperCase() &&
      profile.baud === baud,
  );
}
