# Installing and setting up the Yowza! app

Yowza! runs on Android 10 or newer. It isn't on an app store yet; you install the APK
directly.

## 1. Install

1. On your phone, download **[yowza.apk](https://github.com/thunderrabbit/yowza/releases/latest/download/yowza.apk)**
   (always the latest release) and open it.
2. The first time, Android asks you to allow installs from your browser. Allow it.
3. If Play Protect warns about an unknown app, choose **More details → Install anyway**.

Updates install the same way, over the existing app.

> **After every update**, Android switches full-screen alerts off for apps installed
> outside an app store, and apps can't switch it back on themselves. Yowza! posts a
> notification after an update if this happened; tap it and switch the setting back on.

## 2. Allow alerts

Open Yowza!. Under **Alerts need setup**, tap the button next to anything marked ❌:

| Setting | Why |
|---|---|
| **Notifications** | Needed for any alert. |
| **Full-screen alerts** | Lets an alert take over the locked screen (Android 14+). |
| **Override Do Not Disturb** | Lets alerts ring through Do Not Disturb. Turn on *Override Do Not Disturb* in the Alerts channel settings that open. |
| **Unrestricted battery** | Stops Android from cutting the connection to your mail while the phone is idle. Yowza! mostly waits on an open connection, so the extra battery use is small. |

Then try **Test alert in 10 s** and lock the phone: the orange alert should take over the
screen with sound.

## 3. Connect your mail (JMAP)

Yowza! reads mail over [JMAP](https://jmap.io/), e.g. from Fastmail. It only needs
**read-only** access, and it only ever fetches sender, subject and time, never the body.

1. In Fastmail: **Settings → Privacy & Security → Manage API tokens → New API token**.
   Choose **read-only** access and the **Email** scope only.
2. In Yowza!: **Connect mail**, paste the token, **Save and connect**.
   For another JMAP server, also change the session URL.
3. The status should read **Connected as …**. A ⚠️ line means the token can change your
   mail; make a read-only one instead.

The token is stored encrypted with the Android Keystore and is never logged or backed up.

## 4. Rules

**Edit rules** decides which mail raises an alert. Each rule has a name (shown on the
alert), an on/off switch, *match all* or *match any*, and conditions on **From**, **To**,
**Subject** or **Anywhere** ("contains"). The mail server does the matching.

The first install creates a few example rules (with `example.com` addresses). Edit or
delete them.

**Backups:** rules are included in Android's backup to your Google account (the token is
not). **Save rules to a file…** keeps a copy, e.g. in Google Drive, rewritten after every
change; **Load rules from a file…** restores it.

## 5. Listen for new mail

Switch on **Listen for new mail**. Yowza! keeps one push connection to your mail server and
checks your rules within seconds of new mail arriving. A small "Yowza! is listening"
notification stays in the shade. It reconnects by itself, catches up on anything missed,
and restarts after a reboot or an update.

**Check now** runs the rules by hand. **Show recent matches** lists recent matching mail
without alerting, and tapping one tests its alert.

## 6. Optional

- **Server inbox:** alerts from your own scripts (PHP errors, cron jobs). Install the
  inbox on your web host first: see [server/README.md](../server/README.md). Then in
  Yowza!: **Server inbox**, enter the full URL of `alerts.php` and a read key, **Save and
  connect**.
- **Quiet hours** (in **Edit rules**): pick a nightly window. Rules with *Can wake me during
  quiet hours* switched off wait, and their alerts go off together when quiet hours end.
- **Time-zone alarms:** an alarm pinned to a city's clock, e.g. "09:00 Asia/Tokyo",
  rings at that real moment wherever you are, and keeps working when you travel or the
  phone changes time zone. Set a label, time, zone and repeat days; the list shows each
  alarm's next ring in your local time. They ring with the same full-screen alert and
  ignore quiet hours.
- **Repeats:** after an alert, identical copies (same sender and subject) for 30 minutes
  don't ring again; a silent "Repeats muted" notification counts them.

## When something's wrong

- **Health and log** (home screen) shows the connection, last push and check, permission
  states and a log of recent events. It never records email contents or keys.
- If Yowza! can't log in, or can't reach your mail for 15 minutes, it posts a normal
  notification saying so.
