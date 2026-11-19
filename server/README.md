# Yowza! alert inbox (server)

A tiny alert inbox for [Yowza!](../README.md). Your own scripts (PHP error handlers, cron
jobs, anything that can make an HTTP request) **post** alerts here; the Yowza! app on your
phone **polls** for new ones and raises a big full-screen alert.

- Plain PHP + MySQL. Runs on cheap shared hosting: no daemon, no Firebase, no ntfy.
- The phone only ever polls. Nothing on the server pushes to the phone.
- Identical alerts collapse: a PHP error firing 500 times becomes one alert with a count.

This folder is licensed under the **GNU Affero General Public License v3** (see
[LICENSE](LICENSE)). The Android app in the rest of the repository is GPLv3. Every response
from the inbox includes a link to this source code.

Requires PHP 8.1+ with PDO MySQL, and MySQL 5.7+ or MariaDB 10.3+.

## Files

| File | What it does |
|---|---|
| `alerts.php` | The only web-facing file. `POST` adds an alert, `GET` lists new ones. |
| `lib.php` | Database, key checks, collapsing, pruning. |
| `config.example.php` | Copy to `config.php` and fill in. `config.php` is never committed. |
| `install.php` | Command line: creates the tables (or prints the SQL). |
| `make-key.php` | Command line: makes a new key and the hash to put in `config.php`. |
| `.htaccess` | Blocks web access to everything except `alerts.php` (Apache). |

## Install

1. **Upload this folder** to your web host. Call it whatever you like, e.g.
   `example.com/pager/`. Nothing depends on the folder name.
2. **Create a MySQL database** (or use an existing one).
3. **Configure:** copy `config.example.php` to `config.php` and set the database login.
   Tables are named with `table_prefix` (default `yowza_`), so they're easy to spot in a
   shared database.
4. **Create the tables:**
   ```sh
   php install.php            # creates the tables
   php install.php --print    # or just print the SQL, e.g. for phpMyAdmin
   ```
5. **Make keys** and paste each printed line into `config.php`:
   ```sh
   php make-key.php read phone          # the key the app uses to fetch alerts
   php make-key.php write my-website    # one per source that sends alerts
   ```
   `config.php` stores only hashes. Keep the printed keys somewhere safe (e.g. a password
   manager); they can't be recovered, only replaced.
6. **In the app:** Server inbox → enter the full URL of `alerts.php`
   (e.g. `https://example.com/pager/alerts.php`) and the read key.

## Sending an alert

```sh
curl -X POST https://example.com/pager/alerts.php \
  -H "Authorization: Bearer YOUR_WRITE_KEY" \
  -H "Content-Type: application/json" \
  -d '{"title": "Backup failed", "body": "disk full on /var", "priority": "high"}'
```

| Field | Required | Notes |
|---|---|---|
| `title` | yes | Shown big on the phone. Up to 255 characters. |
| `body` | no | Short detail, up to 1000 characters. |
| `priority` | no | `normal` (default) or `high`. |
| `dedupe_key` | no | Alerts with the same key from the same source within `collapse_minutes` merge into one, with a count. Defaults to the title. |

Form-encoded posts (`-d title=...`) work too. The source name comes from the key, not from
the request, so a source can't pretend to be another.

If your host strips the `Authorization` header, send the key as `X-Yowza-Key: YOUR_KEY`
instead.

## Fetching (what the app does)

```sh
curl "https://example.com/pager/alerts.php?after=0" -H "Authorization: Bearer YOUR_READ_KEY"
```

The reply lists alerts changed since cursor `after`, plus a new `cursor` to send next time.
A collapsed alert whose count grew comes back again with its new count. A request without
`after` returns only the current cursor, so a newly set-up phone doesn't receive old alerts.

## Housekeeping

- Alerts older than `retention_days` (default 30) are deleted automatically.
- Each source may create at most `rate_limit_per_minute` new alerts a minute. Beyond that,
  extras collapse into one "Too many alerts from …" alert instead of being dropped.
