# JawnOS fork of termux-app

Base: upstream tag v0.118.3, branch jawnos.
Purpose: host and supervise jawnos (the Perl app) on Android; see ../PLAN.md.

## Deltas from upstream (keep minimal; list every change here)

1. app/build.gradle: build arm64-v8a only.
   - defaultConfig: added ndk { abiFilters "arm64-v8a" }
   - downloadBootstraps: removed arm, i686 and x86_64 entries (aarch64 only)
2. Planned: branding, the JawnOS overlay dot service, boot autostart,
   a dashboard activity.

## Build (local)

    JAVA_HOME=<jdk-11 dir> ANDROID_SDK_ROOT=<android-sdk dir> \
    TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 \
      ./gradlew -p . :app:assembleDebug --no-daemon

TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 makes Gradle emit one universal APK
instead of per-ABI split APKs; release builds default to a single APK.
