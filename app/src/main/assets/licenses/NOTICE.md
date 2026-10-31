# JawnOS app — licenses and notices

This APK is the JawnOS Android host app. It is distributed under the GNU
General Public License, version 3 (see `GPL-3.0.txt`), because it is a
derivative of Termux's `termux-app` and includes sources from `termux-shared`
that are GPL-3.0-only. The corresponding source for this build is
https://github.com/jonathanferwerda/termux-app (branch `jawnos`); the app's
`versionName` names the release whose sources match this APK.

## Components in this APK

- `termux-app` app sources and `jawnos-shared` (the fork of `termux-shared`) —
  GPL-3.0-only, with the MIT, Apache-2.0 and GPLv2-with-Classpath exceptions
  listed in `jawnos-shared/LICENSE.md`.
- `terminal-view` and `terminal-emulator` — Apache-2.0, from
  jackpal/Android-Terminal-Emulator (see `Apache-2.0.txt`).
- AndroidX, Guava, Markwon and android-smsmms — Apache-2.0; their notices are
  carried by those projects.
- Google Play services code scanner — Google's own terms, not GPL.
- libtailscale — BSD-3-Clause.

## The runtime archive (downloaded on first run)

The app does not bundle the runtime. It downloads the archive pinned in
`assets/jawnrt.json`, verifies its sha256, and unpacks it. That archive is
built from Termux packages and carries its own licenses, including:

- perl (Artistic-1.0 or GPL), openssl (Apache-2.0), sqlite3 (public domain),
  curl (curl license), OpenSSH (BSD), zip (Info-ZIP), xz (public domain),
  zstd (BSD), ImageMagick (ImageMagick license), ffmpeg (LGPL-2.1-or-later, or
  GPL if the build enables GPL-only codecs — see the Termux recipe)
- bash, coreutils, findutils, sed, grep, gawk and rsync — GPL-3.0
- git — GPL-2.0-only

Those are separate programs aggregated in the archive; they do not change the
license of this app. Their source is the Termux-packages recipe and the
upstream releases for the versions named in `releases/jawnrt.json`:
https://github.com/termux/termux-packages

The full GPL-3.0 text is `GPL-3.0.txt`; the Apache-2.0 text is
`Apache-2.0.txt`.
