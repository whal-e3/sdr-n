# Local upload signing

The persistent RSA-4096 upload key was generated outside the checkout. Its
public certificate is `upload-certificate.pem`; SHA-256 fingerprint:

`D2:B4:A2:DE:36:31:05:F7:58:40:40:A4:B8:2A:97:CE:81:42:BF:1A:70:AB:D1:91:E8:CF:33:F5:72:0E:82:8C`.

The certificate is public. The private keystore/password are never committed.
The self-signed certificate is normal for an Android upload key; it is separate
from the catalog's Ed25519 key and Google's eventual Play App Signing key.

## Existing machine

- Linux/WSL: `~/.local/share/OrbitScope/signing/`, directory mode 700,
  `orbitscope-upload.jks` and `upload-signing.json` mode 600.
- Windows backup: `%LOCALAPPDATA%\OrbitScope\signing\`, containing the
  keystore and `upload-password.xml`. The latter is protected with Windows
  user-bound DPAPI, not a plaintext password file.

With the documented JDK/SDK configured, build in WSL:

```bash
python3 tools/build_release.py
```

Or in PowerShell on the same Windows user account:

```powershell
.\tools\build_release.ps1
```

The scripts pass credentials in the child environment, without putting passwords
in command arguments or printing them. The PowerShell script restores previous
environment values afterward. Windows also needs a working JDK 17/Android SDK;
this session's verified toolchain is in WSL.

## Backup before uploading

Keep an encrypted backup of the keystore and its password outside this computer.
The local Windows copy protects against losing the WSL installation but is not
an off-device backup. DPAPI secrets generally cannot be decrypted by a different
Windows account or after losing the original account's keys. Export to a trusted
password manager/encrypted backup while signed in as this user; do not paste
passwords into chat or add them to the repository.

For another machine, create a private JSON file containing the four documented
`ORBIT_UPLOAD_*` values and invoke `tools/build_release.py --signing-config PATH`.
Use restrictive file permissions and remove temporary plaintext exports.

When registering Play App Signing, let Google manage the app-signing key and
use this key for uploads. Losing the upload key may be recoverable through
Play's reset process; preserving it avoids that disruption. A standalone APK
signed with this upload key will not necessarily update an installation signed
by Google's different app-signing key.
