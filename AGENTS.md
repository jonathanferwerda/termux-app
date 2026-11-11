# AGENTS.md — the JawnOS Android app (termux-app fork)

This repo (`~/jawnos-app/termux-app`, branch `jawnos`, remote `fork`) is the
Android app that hosts and supervises jawnos on a phone/tablet. `JAWNOS.md`
lists every fork delta. Read this first.

## Rule 1 — every change to the APK is a version change

Any change that goes into the APK — Java, `res/`, `AndroidManifest.xml`,
`app/build.gradle` — MUST, in the same change:

1. **Bump `versionCode`** (integer, +1) **and the `versionName` patch** in
   `app/build.gradle`, and
2. **build, stage and publish** it.

Why: the whole update path compares an APK's `versionCode` against the installed
one — the in-app updater (the dashboard's "Update app"), the peer/GitHub sync
(`subs::app_release_sync`), and the published manifest (`scripts/app_publish.pl`).
A rebuild that reuses a `versionCode` reads as **"up to date"** and **no device
will take it** (this is exactly why a build once sat uninstalled while the app
kept saying it was current). Never reuse or re-ship a number; always step above
the last release (1308 / 0.118.310).

A change that never enters the APK — this file, `JAWNOS.md`, `README.md` — is not
a version change.

## The one-step way (preferred)

From the jawnos repo (`~/jawnos`):

    perl scripts/app_build.pl      # bumps versionCode + versionName, builds the RELEASE-signed APK, stages it
    perl scripts/app_publish.pl    # publishes the newest staged APK + manifest to GitHub

`app_build.pl` builds the **release** variant whenever `keystore.properties` is
present, and refuses to stage an APK whose certificate is not that keystore's.
`--debug` builds the dev variant, which no phone running a release build will
install over - use it only to develop against the app.

Then commit and push this repo (app_build.pl leaves it dirty on purpose — it only
builds and stages, so the bump is yours to commit):

    git -C ~/jawnos-app/termux-app add -A
    git -C ~/jawnos-app/termux-app commit -m "v<versionName> - <summary>"
    git -C ~/jawnos-app/termux-app push fork jawnos

Skipping the publish leaves peers and GitHub still advertising the previous
versionCode, so devices will not see the new build.

## Building by hand

If you build with gradlew directly, **bump `versionCode`/`versionName` in
`app/build.gradle` yourself first**, then stage and publish:

    JAVA_HOME=/home/jawn/jdk/jdk-11.0.32.1+1 \
    ANDROID_SDK_ROOT=/home/jawn/android-sdk \
    TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 \
      ./gradlew -p . :app:assembleDebug --no-daemon --max-workers=4

    perl ~/jawnos/scripts/app_release.pl <the-universal-apk>
    perl ~/jawnos/scripts/app_publish.pl

## Conventions
- Cap Gradle at 4 workers (`--max-workers=4`) — this laptop is shared.
- Build arm64-v8a only; keep the fork's deltas minimal and log each one in
  `JAWNOS.md`.
- `scripts/app_build.pl` auto-detects the SDK (`~/android-sdk`) and a JDK
  (`~/jdk/jdk-*`); `scripts/app_publish.pl` needs an authenticated `gh`.

## The tailnet: the key is the gate

The app embeds libtailscale (there is no `tailscale` CLI on Android), so the
house tailnet is controlled here, not by the Tailscale app.

- **Never ship a key.** No preauth key belongs in the APK, in `res/`, or in a
  tracked file. A key is provisioned per device, once.
- **Mint a key on the cloud** (Headscale lives there): `sudo headscale
  preauthkeys create -u 1 --expiration 1h -o json`. Keep it **single-use** (no
  `--reusable`) and short-lived. `headscale preauthkeys list` shows the unused
  ones; `headscale preauthkeys expire -i <id>` kills one.
  Three long-lived reusable keys (2026-10-20) sat unused until 2026-11-02; they
  were expired because anyone holding one could join the tailnet.
- **Provision a device over adb** (the receiver accepts only the shell UID):
  `adb shell am broadcast -a com.jawnos.app.JAWN_TAILNET -n
  com.jawnos/com.jawnos.app.JawnWakeReceiver --es control_url <url> --es auth_key <key>`.
  Without adb, tap *Join / connect* in the dashboard and paste it (blank means
  browser approval). The server itself is the **Tailnet server** field in the
  same card: it stores an `https://` URL, and without one the node reports
  `needs_server` and does not start.
- **Revoke**: `headscale nodes delete -i <id>` (or `headscale nodes expire`).
- **Self-healing**: the node state lives in `files/tailnet/`, so a joined device
  reconnects with no key; only a lost state needs a new one, and the dashboard
  then says `needs_key`. After a reboot the tunnel returns on the **first
  unlock**, not before: `BOOT_COMPLETED` is delivered then, and jawn itself is in
  credential-encrypted storage. Making it earlier would need a
  `LOCKED_BOOT_COMPLETED` receiver plus device-protected prefs and state - a
  security downgrade for a device that has nothing to serve before unlock, so it
  is deliberately not done. A device that lost its state registers as a NEW node
  (`tablet` becomes `tablet-1`): delete the stale node and rename the new one
  (`headscale nodes delete -i <old>`, `headscale nodes rename -i <new> <name>`). `JawnTailnetService` retries on its own (5s/15s/60s/5m,
  then 15m, via an allow-while-idle alarm), and `files/tailnet/status.json` is
  what the house manager reads to show the real state.
- **Its own client**: do not route this through the Tailscale app or a CLI; the
  `vpn()`/`tailnetStatus` bridge is the only source of truth on Android.
