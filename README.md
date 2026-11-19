# Yowza! 👋 ✉️ 🥳🎉

**Have you stopped watching email but still need to know when the rare important message arrives?**

Yowza! is an Android app that stays quiet until something matters, then takes over the screen with a big alarm-style alert that gets through Do Not Disturb. 😱🙀🤯

## What it does

- **Email alerts via JMAP** (e.g. Fastmail). You write rules on sender, recipient, subject or text, the mail server does the matching, and Yowza! raises a full-screen alert within seconds of a match arriving. Read-only token; only sender, subject and time are ever fetched.
- **Alerts from your own scripts.** A tiny self-hosted PHP + MySQL [server inbox](server/README.md) that your scripts post to and the phone polls. Runs on cheap shared hosting: no daemon, no Firebase, no ntfy.
- **No alert storms.** Identical alerts collapse into one "×N" alert, and repeats within 30 minutes are muted.
- **Quiet hours** per rule: some rules can wake you, the rest wait until morning.
- **Visible failures.** A health screen and log, and a notification if Yowza! can't reach your mail.

No Google Play Services or Firebase.

## Install

- **The app:** download **[yowza.apk](https://github.com/thunderrabbit/yowza/releases/latest/download/yowza.apk)** on your phone and open it. Full setup (permissions, mail token, rules, listening): **[docs/INSTALL-APP.md](docs/INSTALL-APP.md)**.
- **The server inbox** (optional, for alerts from your own scripts): **[server/README.md](server/README.md)**.

Every push to `main` builds a new app release via GitHub Actions.

## Build it yourself

Requires JDK 17+ and the Android SDK (API 36).

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed when these environment variables are set: `YOWZA_KEYSTORE` (path), `YOWZA_KEYSTORE_PASSWORD`, `YOWZA_KEY_ALIAS`, `YOWZA_KEY_PASSWORD`. Without them, release builds use the debug key. In CI they come from one repository secret, `YOWZA_SIGNING`, holding `<password>:<base64 of the keystore>`; without it, CI still builds but doesn't publish a release.

## License

The app is free software under the [GNU General Public License v3](LICENSE) or later. The server inbox in [`server/`](server/) is under the [GNU Affero General Public License v3](server/LICENSE).
