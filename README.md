# Janus for Android

Janus connects your Android phone with your Mac: notifications, calls, files and photos, clipboard, the phone's
calendar on the Mac, notes synced both ways, screen mirroring, Live Activities and more, on the same Wi-Fi or, with
Janus Cloud, from anywhere. It also turns your phone into the remote for an Android TV. **Janus for Mac**
([janus-mac](https://github.com/vivere-181/janus-mac)) is the other half.

Janus is named after the Roman god of doorways, who looks both ways: one app, two sides.

**[Download the latest release](https://github.com/vivere-181/janus-android/releases/latest)** (an APK for phones and Android TVs).

## Based on KDE Connect

Janus is a fork of [KDE Connect for Android](https://invent.kde.org/network/kdeconnect-android) and speaks the KDE
Connect protocol (version 8) with its own extensions. Most of the networking, pairing, encryption and the original
plugins are KDE Connect's work, by the KDE Connect authors; see the copyright headers in each file. Janus adds a new
interface (Material 3 Expressive), its own features and the companion messages it exchanges with Janus for Mac.

Janus is not affiliated with or endorsed by KDE. "KDE" and "KDE Connect" are trademarks of KDE e.V.

## Features

- **Pairing by QR code**: scan the code Janus for Mac shows; no codes to compare. A simple first run: allow the basics
  in one go, then turn on the features you want, each asking only for what it needs.
- **Your devices and other people's**: your own devices get everything; someone else's device only gets
  sharing, with Accept / Decline for files.
- Notifications (with replies and actions), calls (with a live call card on the Mac), SMS, contacts.
- Send files, photos, links and text; a share sheet and a send sheet; recent transfers.
- Clipboard: what you copy on the Mac is ready to paste on the phone, and the other way round.
- **Janus Cloud** (optional): when the phone and the Mac aren't on the same network, files, the clipboard, notes,
  notifications and Mac controls go through an end-to-end encrypted relay; the relay can't read them.
- Notes with formatting, checklists, attachments and tables, synced with the Mac, plus a home-screen widget.
- The phone's calendar on the Mac (countdown to the next meeting, agenda, Join links).
- Screen mirroring to the Mac (with Janus for Mac).
- Live Activities: timers, deliveries, rides and other ongoing things on the Mac.
- Phone ↔ phone: notifications, calls, controls and remote camera between two phones running Janus.
- Quick Settings tiles, home-screen widgets, a "Find my Mac" and Mac controls (lock, mute, screenshot).
- **Android TV**: install Janus on the TV too. The phone becomes its remote (D-pad, touchpad, keyboard, voice search,
  volume, the TV's apps), and the TV receives AirPlay screen mirroring from a Mac, iPhone or iPad.

## Install

Janus needs Android 7.0 or newer.

1. On the phone (or the TV), download `Janus-<version>.apk` from [Releases](https://github.com/vivere-181/janus-android/releases) and open it.
2. Android asks once to allow installs from your browser or file manager; allow it, then tap **Install**. Android may
   warn that the app isn't from the Play Store; that's expected for an app installed from a file.
3. Open Janus and scan the code Janus for Mac shows.

On Android 13 and later, if the notification-sync switch is greyed out, open App info → ⋮ → **Allow restricted
settings**, then try again (Android asks this of apps installed from a file).

Updates install the same way, over the previous version, keeping your pairings. Every release is signed with the
same key.

## How it pairs with the Mac

Janus for Mac shows a QR code (on its welcome screen and in Add Device). Scan it in Janus, or with the phone's camera,
which opens Janus. The phone connects straight to the Mac, checks the Mac's certificate against the code, and pairs
with the code's one-time key, so there's nothing to compare. Both devices need to be on the same network for this.

You can also pair over Wi-Fi from either side without the QR code; then both show a verification code to compare.
The companion messages are documented in
[PROTOCOL.md](https://github.com/vivere-181/janus-mac/blob/main/docs/PROTOCOL.md) in the Mac repository.

## Building

Requirements: JDK 17 and the Android SDK (Android Studio works too).

```sh
./gradlew assembleDebug        # → build/outputs/apk/debug
./gradlew testDebugUnitTest    # unit tests
```

Release builds are signed with your own key: create `keystore.properties` (not in the repository) with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, builds use the debug key.
`tools/release.sh <version>` makes a release (version, tests, signed APK, tag; `--publish` uploads it).

## Licence

Janus is free software under the **GNU General Public License v3.0** ([LICENSE](LICENSE)). KDE Connect's files keep
their original licence headers (GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL); see `LICENSES/`.
Bundled assets: Google Sans Flex (SIL Open Font License, `src/brand/OFL-GoogleSansFlex.txt`), Material Symbols
(Apache 2.0). The Janus icon sources are in `icon/`. MMS uses android-smsmms (Apache 2.0, `libs/android-smsmms`). The AirPlay receiver for Android TV (`src/airplay`) is
UxPlay via [android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0), with libplist,
OpenSSL, Oboe and FFmpeg's ALAC decoder under their own licences; see `src/airplay/README.md`.

Apple, Mac and MacBook are trademarks of Apple Inc.; Samsung and Galaxy are trademarks of Samsung Electronics.
They're used only to describe the devices Janus works with.
