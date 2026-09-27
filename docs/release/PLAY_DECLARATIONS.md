# Play Console declaration worksheet

Prepared for the default preview: no catalog endpoint, no ads/login/analytics.
Review against the exact uploaded build and any endpoint added later.

## App access, ads, audience and rating

- No login/account, paywall or invitation is required to open the app.
- External hardware is required for USB sample streaming. Tracking, manual
  location, map and synthetic tone are accessible without an SDR.
- Demo satellite reception is deliberately disabled. Explain this in review
  notes; do not promise the reviewer a working decoder without a current signed
  catalog and a suitable available signal.
- No ads. The app is an experimental technical tool; do not claim it targets
  children. The owner selects the actual intended age groups.
- Complete the IARC questionnaire from actual content: no game violence,
  simulated gambling or app-provided user chat/content-sharing system. Do not
  choose a final rating yourself; Play computes it from the questionnaire.
- No microphone permission or microphone recording; received FM audio is output.

## Data safety

Coordinates, selected orbit files, filenames, USB information, samples, audio
and decoded packets stay on-device in the current app. Automatic location is
requested while the UI is visible. No background-location permission, account,
ads SDK, developer analytics or automatic crash upload is present. Android
cloud backup is disabled. Users can delete app data through Android's Clear
storage or uninstall; originals selected with the file picker remain untouched.

An explicit CelesTrak lookup transmits NORAD ID and necessarily exposes the
connection IP/request metadata to CelesTrak. Do not ignore this external
provider when completing the form. Determine its applicable collection/sharing
and ephemeral-processing treatment using the form's current definitions and
provider policies. If a hosted catalog is enabled, document its operator,
logs/retention and all other transmitted data before answering the form.
A blanket 'no data collected' selection has not been approved by this worksheet.

Privacy policy must be public and accessible in-app, identify the developer
name used on Play, and include a working contact mechanism. Final support
email/account identity must come from the owner. No account-deletion URL is
needed for an app that does not create app accounts.

## Foreground services

Manifest service types: `connectedDevice|mediaPlayback`.

- connectedDevice: user presses Receive after granting USB access; the service
  streams IQ from an attached USB SDR. A visible ongoing notification exposes
  Stop. The independent short SDR tester runs while the Activity is visible
  and stops when the app leaves the foreground; it does not start this service.
- mediaPlayback: a user-started supported FM receiver plays demodulated audio
  and may continue while the app is not visible. Notification Stop releases
  USB/audio resources. The default tester selects Decoder Off and plays no audio.

Prepare a short real-device video showing user initiation, USB permission,
ongoing notification and Stop. Demonstrate FM playback only with a valid
catalog and known signal; do not use a spectrum trace as evidence of audio or
packet decoding. Record the video privately and remove personal notifications.
No genuine RF decoding demonstration is available from the existing tests.

## Fresh-install review notes

OrbitScope is an experimental tracking and USB-SDR tester preview. No login.
Location permission is optional; enter manual coordinates or use the offline
map. In Signal, choose Test tone for synthetic plots without hardware, or SDR
tester with an RTL-SDR/HackRF attached through OTG. Receiver controls require
a verified current signed catalog; the historical bundled demo disables them.

A service update/deployment, public policy hosting service, store upload or
production rollout has not been authorized/performed by this worksheet.
