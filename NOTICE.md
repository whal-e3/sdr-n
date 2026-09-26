# Notices and data attribution

The source written for OrbitScope is offered under
`GPL-3.0-only`; see [LICENSE](LICENSE). This does **not** relicense the
third-party code or catalog data below. Their original copyright and license
notices remain in their source files.

## Bundled native source

- **libusb 1.0.30 and libusb-cmake** — LGPL-2.1-or-later. Source and the
  upstream license texts are under
  `app/src/main/cpp/third_party/libusb-cmake/`. The Android build links libusb
  statically; a binary release must include its corresponding source and the
  materials needed to rebuild/relink a modified version under the applicable
  LGPL terms. Upstream: <https://github.com/libusb/libusb-cmake> and
  <https://github.com/libusb/libusb>.
- **RTL-SDR Blog rtl-sdr fork** — principally GPL-2.0-or-later. Its original
  source headers and GPLv2 text are under `app/src/main/cpp/third_party/rtl-sdr/`.
  The bundled `src/getopt/` files have their own LGPL-2.1-or-later notices;
  they are not part of the Android native build. This project modifies the
  receiver library to open an Android-authorized USB file descriptor.
  Upstream: <https://github.com/rtlsdrblog/rtl-sdr-blog>.
- **Great Scott Gadgets libhackrf** — BSD 3-clause terms in the headers of
  `app/src/main/cpp/third_party/libhackrf/hackrf.c` and `hackrf.h`. This
  project modifies the library to open an Android-authorized USB file
  descriptor. Source and binary distributions must retain/reproduce the
  copyright, conditions, and disclaimer from those headers. Upstream:
  <https://github.com/greatscottgadgets/hackrf>.

Pinned upstream revisions and the native-source modifications are described
in [app/src/main/cpp/third_party/README.md](app/src/main/cpp/third_party/README.md).

## Other app dependencies

- **Orekit 12.1.2** and its **Hipparchus** dependencies — Apache License 2.0.
  <https://www.orekit.org/> and <https://www.hipparchus.org/>.
- **Bouncy Castle Java** (`bcprov-jdk18on`) — MIT-style license, with its
  copyright and permission notice required in copies or substantial portions.
  <https://www.bouncycastle.org/about/license/>.
- **Gson** — Apache License 2.0. The app uses its streaming JSON reader for
  signed catalogs. <https://github.com/google/gson>.
- AndroidX, Kotlin, and other packaged dependencies retain their own licenses.
  Produce and inspect a complete dependency-license inventory, including
  transitive dependencies, and ship applicable texts/notices with any APK or
  other binary release.

These licenses are generally usable with a GPLv3 application, but the
obligations for each bundled component still apply separately. In particular,
do not remove upstream notices or describe the entire repository as solely
GPL-3.0-only.

## Catalog and sample data

- **Orbital elements:** [CelesTrak](https://celestrak.org/), whose
  [usage policy](https://celestrak.org/usage-policy.php) limits update
  frequency and asks clients to download only needed data. No GPL license is
  asserted for these elements by this repository. Confirm terms for public
  republication of a derived catalog before deploying the catalog service.
- **Satellite and transmitter metadata:** [SatNOGS DB](https://db.satnogs.org/about/),
  maintained by the Libre Space Foundation and contributors, licensed
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The catalog
  selects, joins, normalizes, and augments this metadata with curated decoder
  profiles; the SatNOGS-derived data is not relicensed as GPL source code.
- **Protocol/frequency evidence:** Links such as
  [ARISS](https://www.ariss.org/contact-the-iss.html) are included in curated
  profiles where used. They are evidence references, not an endorsement.

The bundled `sample_catalog.json` and `catalog-service/fixtures/demo-catalog.json`
are historical, unsigned demonstrations of the combined catalog format, not
current observing data. Fresh manifests carry their own attribution strings.

## Bundled map outlines

The offline world map includes `app/src/main/assets/maps/ne_110m_land.geojson`,
the Natural Earth 1:110m land polygons (downloaded September 2026 from
<https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson>).
Natural Earth [places its map data in the public domain](https://www.naturalearthdata.com/about/terms-of-use/).
The 1:110m outlines are suitable for regional orientation, not street-level
positioning; selected WGS84 coordinates come from the map projection itself.
