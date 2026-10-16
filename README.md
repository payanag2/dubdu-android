# DubDu Android (experimental)

Native Android companion for the [DubDu Chrome extension](https://github.com/payanag2/dubdu). The app targets Android 10+ and is intended to capture eligible playback audio, send it to Gemini Live Translate, and play translated Persian audio.

## Requirements
- Android 10 (API 29) or newer.
- A Gemini API key with access to the Gemini Live API model used in the app.
- Internet access.
- The playback app must allow Android Audio Playback Capture. Some apps block it.

## Build
Open this repository in Android Studio, or run `gradle assembleDebug` from the repository root with Gradle 8.11+ and JDK 17 installed.

## Use
1. Install the APK from GitHub Releases.
2. Enter your own Gemini API key.
3. Tap **شروع دوبلهٔ زنده** and approve Android's system audio-capture prompt.
4. Start playing media in another app.
5. Tap **توقف دوبله** to end the session.

The API key is stored in the app's private preferences on-device. Do not share the APK with a key embedded in it.

## Automatic APK builds
- Pushing to `main` or running the workflow manually builds a debug APK and updates the rolling `latest` prerelease.
- Pushing a tag such as `v0.1.0` builds an APK and creates a versioned GitHub Release.
- APK build artifacts are also attached to each successful Actions run.

## Status and limitations
This is an experimental first implementation, not a verified replacement for every feature of the browser extension. Live translation latency, model availability, protocol compatibility, and playback capture support must be tested on a physical device. Audio capture is subject to Android's media-projection consent and per-app capture policies. The app does not bypass DRM or apps that prohibit capture.
