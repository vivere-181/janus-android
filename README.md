# Janus for Android

Janus connects your Android phone with your Mac: notifications, calls, files and photos, clipboard, the phone's
calendar on the Mac, notes synced both ways, screen mirroring, Live Activities and more. **Janus for Mac**
([janus-mac](https://github.com/vivere-181/janus-mac)) is the other half.

Janus is named after the Roman god of doorways, who looks both ways: one app, two sides.

## Based on KDE Connect

Janus is a fork of [KDE Connect for Android](https://invent.kde.org/network/kdeconnect-android) and speaks the KDE
Connect protocol (version 8) with its own extensions. Most of the networking, pairing, encryption and the original
plugins are KDE Connect's work, by the KDE Connect authors; see the copyright headers in each file. Janus adds a new
interface (Material 3 Expressive), its own features and the companion messages it exchanges with Janus for Mac.

Janus is not affiliated with or endorsed by KDE. "KDE" and "KDE Connect" are trademarks of KDE e.V.

## Features

- **Your devices and other people's**: your own devices get everything; someone else's device only gets
  sharing, with Accept / Decline for files.
- Notifications (with replies and actions), calls (with a live call card on the Mac), SMS, contacts.
- Send files, photos, links and text; a share sheet and a send sheet; recent transfers.
- Notes with formatting, checklists, attachments and tables, synced with the Mac, plus a home-screen widget.
- The phone's calendar on the Mac (countdown to the next meeting, agenda, Join links).
- Screen mirroring to the Mac (with the Mac app, over Wireless debugging).
- Live Activities: timers, deliveries, rides and other ongoing things on the Mac.
- Phone ↔ phone: notifications, calls, controls and remote camera between two phones running Janus.
- Quick Settings tiles, home-screen widgets, a "Find my Mac" and Mac controls (lock, mute, screenshot).

## How it pairs with the Mac

Install Janus on the phone and Janus for Mac on the Mac, put both on the same network and pair from either side
(both show the same verification code). The companion messages are documented in
[PROTOCOL.md](https://github.com/vivere-181/janus-mac/blob/main/docs/PROTOCOL.md) in the Mac repository.

## Building

Requirements: JDK 17 and the Android SDK (Android Studio works too).

```sh
./gradlew assembleDebug        # → build/outputs/apk/debug
./gradlew testDebugUnitTest    # unit tests
```

Release builds are signed with your own key: create `keystore.properties` (not in the repository) with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, builds use the debug key.

## Licence

Janus is free software under the **GNU General Public License v3.0** ([LICENSE](LICENSE)). KDE Connect's files keep
their original licence headers (GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL); see `LICENSES/`.
Bundled assets: Google Sans Flex (SIL Open Font License, `src/brand/OFL-GoogleSansFlex.txt`), Material Symbols
(Apache 2.0). The Janus icon sources are in `icon/`.

Apple, Mac and MacBook are trademarks of Apple Inc.; Samsung and Galaxy are trademarks of Samsung Electronics.
They're used only to describe the devices Janus works with.
