# AirPlay receiver (TVs)

Janus on an Android TV receives AirPlay (screen mirroring with sound from a Mac, iPhone or iPad; sound alone for
music). Janus's own side is `src/brand/java/ge/connect/airplay/`. This folder holds the receiver it runs, taken from
**android-airplay-server** by jqssun (GPL-3.0, see `LICENSE`), which is built on
[UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0), libplist (LGPL-2.1), OpenSSL (Apache-2.0), Oboe (Apache-2.0) and
FFmpeg's ALAC decoder (LGPL-2.1).

- Source: https://github.com/jqssun/android-airplay-server, tag `v0.0.31`, commit
  `c8defdd70d7e6a04f4f1b71d353653682d594106` (its submodules pin the libraries above).
- `jniLibs/`: the native libraries from that release's `app-release.apk` (sha256
  `c5dce5c29ab52157bdaa406e485f9bfaab7d8d59c523d93622a33b253a28f410`, as listed on GitHub), for `armeabi-v7a` and
  `arm64-v8a`: `libairplay_native.so`, `libcrypto.so`, `liboboe.so`, `libc++_shared.so`. To rebuild them, build that
  tag (`app/src/main/cpp`, NDK 27) and copy the same files from the APK's `lib/`.
- `java/`: the app's JNI bridge (`bridge/`, names fixed by the native code) and its video and audio renderers
  (`renderer/`), unchanged except where marked "Janus" (a nullability fix for SDK 37; the audio debug snapshot left out).
