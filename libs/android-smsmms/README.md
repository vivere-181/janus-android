# android-smsmms (vendored)

The SMS/MMS sending library Janus uses for MMS (group messages, attachments), kept here as a built `.aar` so the app
doesn't fetch it from another project's repository at build time.

- Library: "Android Messaging" by Jacob Klinker (originally github.com/klinker41/android-smsmms), in the version
  maintained for KDE Connect: invent.kde.org/sredman/android-smsmms, tag `kdeconnect-1-21-0`.
- Licence: Apache License 2.0 (`LICENSES/Apache-2.0.txt`).
- `android-smsmms.aar` sha256 `003501737fbc6b39dca672f8e6923983981f8b621ed5bd26807fed127788b1b4`.
- Its dependencies are declared in `build.gradle.kts` (a local `.aar` doesn't bring them): `com.klinkerapps:logger`
  1.0.3 and `com.squareup.okhttp:okhttp` / `okhttp-urlconnection` 2.5.0.
