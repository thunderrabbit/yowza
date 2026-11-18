# Yowza! 👋 ✉️ 🥳🎉

**Have you stopped watching email but still need to know when the rare important message arrives?**

Yowza! is an Android app that stays quiet until something matters, then takes over the screen with a big alarm-style alert that gets through Do Not Disturb. 😱🙀🤯

> Status: early skeleton (step 1 of the build plan). It installs and opens to a home screen; alerts aren't wired up yet.

## What it will do

- **Email alerts via JMAP** (e.g. Fastmail). You write rules on sender, recipient, subject or text, the mail server does the matching, and Yowza! fires a full-screen alert for new matches. Read-only token; only sender and subject are ever shown.
- **Alerts from your own scripts.** A tiny self-hostable PHP + MySQL inbox the phone polls with an API key. Works on cheap shared hosting: no daemon, no Firebase, no ntfy. Repeated alerts (like a PHP error storm) collapse into one "×N" alert.
- **Time-zone-pinned alarms.** "9:00 Tokyo time" fires at the right moment wherever you are.

No Google Play Services or Firebase.

## Install

Download **[yowza.apk](https://github.com/thunderrabbit/yowza/releases/latest/download/yowza.apk)** on your phone and open it. The first time, Android will ask you to allow installs from your browser.

Every push to `main` builds a new release via GitHub Actions.

## Build it yourself

Requires JDK 17+ and the Android SDK (API 36).

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed when these environment variables are set: `YOWZA_KEYSTORE` (path), `YOWZA_KEYSTORE_PASSWORD`, `YOWZA_KEY_ALIAS`, `YOWZA_KEY_PASSWORD`. Without them, release builds use the debug key. In CI they come from one repository secret, `YOWZA_SIGNING`, holding `<password>:<base64 of the keystore>`; without it, CI still builds but doesn't publish a release.

## License

Yowza! is free software under the [GNU General Public License v3](LICENSE) or later. The server-side inbox, when added, will be under the GNU AGPL v3.
