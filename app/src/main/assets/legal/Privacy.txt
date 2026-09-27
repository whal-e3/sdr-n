# OrbitScope privacy policy — draft

This draft describes the current source. Before publication, replace the contact/date fields, review the release's configured catalog provider and SDK inventory, host the final policy publicly, and expose the final policy inside the app. This draft is not a published privacy policy.

Effective date: **[release date]**  
Developer: **[name shown on the store listing]**  
Privacy contact: **[developer support email or private contact form]**

## Location

OrbitScope uses location supplied by Android, with your permission, to calculate satellite visibility, pointing and Doppler correction on your device. You can use manual coordinates or the offline map instead. The app stores the selected coordinates and location preference in its private storage. The current app does not send these coordinates to the developer or its catalog/orbit endpoints. New automatic location updates are requested while the app is visible; it does not request Android's background-location permission.

Android and the device's location providers have their own settings and privacy practices.

## SDR and received signals

With your USB permission, OrbitScope reads samples from an attached supported SDR. It processes spectrum/IQ views and supported audio/packet signals on your device. Recent receiver status and decoded packet text are kept in memory for display. The current app does not upload SDR samples, received audio, decoded packets or USB device information. It does not record your microphone. A manually started receiver can continue with a visible foreground-service notification and Stop control.

## Imported files and saved settings

When you choose an orbit file using Android's file picker, OrbitScope reads that selected file and saves parsed orbital records and their source filename in private app storage. It also stores downloaded/verified catalog data, orbit lookup results and app preferences. The current app does not upload imported files or their filenames. Android cloud backup is disabled for the app.

## Network requests

An explicitly requested NORAD-ID lookup sends that satellite ID to CelesTrak over HTTPS. If a catalog endpoint is configured in the release, the app requests catalog and signature files from that endpoint. The providers necessarily receive the connection's public IP address and request information; their server logging and retention depend on the provider.

**Before publication, identify the configured catalog provider, its privacy policy and its actual logging/retention practices here.** The current development build has no catalog endpoint configured. CelesTrak lookups and catalog downloads do not include the app's saved observer coordinates, imported files or SDR captures.

## Accounts, advertising and analytics

The current app has no app account or login, advertising SDK or developer analytics/crash-report upload integration. Store/platform services operate under their own privacy policies. Revise this policy and the store declarations if these features are added.

## Retention and deletion

Local settings and cached records remain until replaced, cleared through available app controls, or removed through Android's **Clear storage** or uninstall action. Clearing app storage removes the app's local catalogs, imported records and settings; it does not delete an original file you selected elsewhere. Receiver display data is temporary in-process state. Local Android diagnostic logs are managed by the operating system. Network-provider retention is described above and must be confirmed for the released catalog service.

## Permissions and contact

You can deny or revoke location, USB and notification access using Android's controls. Manual coordinates and non-hardware views remain available where applicable; USB reception needs access to the selected SDR. Contact the developer using the privacy contact above for questions about the released app.
