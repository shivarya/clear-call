---
name: clearcall-deploy-api
description: Deploy the ClearCall PHP backend to the shivarya.dev cPanel host and verify it — upload server files, run composer install on the host, apply DB migrations, and health-check the live API. Use when deploying or updating the ClearCall server.
---

Deploy the ClearCall **PHP** API (`server/`) to cPanel at `https://shivarya.dev/clear_call/`. PHP 8 + MySQL front-controller (`index.php` + `.htaccess`), Composer deps (firebase/php-jwt + google/apiclient).

**Connection details (SSH host / user) are intentionally NOT in this file** — this repo is public. Use the private monorepo root's `scripts/connect_ssh.ps1` / `CLAUDE.md` (not part of this repo) to reach the host; the same shivarya.dev cPanel account as diet-plan / ps5-tracker applies. The steps below use `ssh cpanel` as a stand-in for that configured connection.

**Deploy dir**: `~/public_html/shivarya.dev/clear_call` (directly under the docroot — unlike ps5-tracker/expense-tracker, no top-level folder + symlink needed). **DB**: database `clear_call`, user `clearcall` — **no cPanel-account prefix on this host** (verified empirically; trust live `uapi Mysql list_databases` over older prefixed conventions).

## Updating an existing deployment (the common case)

1. Tarball `server/` excluding secrets + vendor + logs, copy up, extract in place (`.env` is excluded so the host's real one survives):
   ```bash
   tar -czf /tmp/clearcall_server.tar.gz --exclude='server/.env' --exclude='server/vendor' --exclude='server/*.log' -C "c:/Users/Ash/Documents/Projects/apps/clear-call" server
   scp /tmp/clearcall_server.tar.gz cpanel:~/clearcall_server.tar.gz
   ssh cpanel "cd ~/public_html/shivarya.dev/clear_call && tar -xzf ~/clearcall_server.tar.gz --strip-components=1 -C . && rm ~/clearcall_server.tar.gz"
   ```
2. **Composer on the host** (never upload local `vendor/`): `ssh cpanel "cd ~/public_html/shivarya.dev/clear_call && composer install --no-dev --optimize-autoloader"`.
3. **DB migrations** — if `database/schema.sql` changed, write a numbered file in `database/migrations/` and apply it manually (don't re-run `schema.sql` on a live DB). Apply without printing the password:
   ```bash
   ssh cpanel "cd ~/public_html/shivarya.dev/clear_call && eval \$(grep -E '^DB_' .env | sed 's/^/export /') && mysql -u \"\$DB_USER\" -p\"\$DB_PASS\" \"\$DB_NAME\" < database/migrations/2026-07-06_devices_platform.sql"
   ```
4. **Never overwrite the host `.env`** — it holds the real production DB password, JWT secret, LiveKit keys, and (once set) `GOOGLE_CLIENT_ID` + `FCM_*` / `APNS_*`. It's generated once at first deploy.

## Secrets on the host (outside the webroot)

- **FCM service-account JSON** and (later) the **APNs `.p8`** live in a private `~/secrets/clear_call/` dir (dir `700`, files `600`) — *outside* `public_html` entirely, referenced by absolute path in `.env` (`FCM_SERVICE_ACCOUNT_PATH`, `APNS_KEY_PATH`). Stronger than `.htaccess`-only denial.

## First-time setup (already done for the initial deploy)

1. DB + user via `uapi Mysql create_database name=clear_call` / `create_user` / `set_privileges_on_database` over SSH — no cPanel web UI needed, no account prefix on this host.
2. Extract `server/` into the deploy dir; `composer install --no-dev`.
3. Host `.env` (chmod 600): `DB_*`, generated `JWT_SECRET`, real `LIVEKIT_*`, `ALLOW_DEV_LOGIN=false`, `GOOGLE_CLIENT_ID` (web client), `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT_PATH`. APNs vars stay empty until an Apple `.p8` exists (see `docs/IOS_APP_PLAN.md`).

## Verify (read-only — never send a real call/push just to confirm a deploy)

```powershell
Invoke-RestMethod https://shivarya.dev/clear_call/health                                   # 200 healthy
Invoke-RestMethod https://shivarya.dev/clear_call/contacts                                  # 401 (routing reaches the app, not the portfolio SPA)
try { Invoke-RestMethod https://shivarya.dev/clear_call/auth/login -Method Post -Body '{"as":1}' -ContentType 'application/json' } catch { $_.Exception.Response.StatusCode }  # 410 (dev login correctly disabled in prod)
```
`.env` / `composer.json` should 403; plain `http://` should 301 → `https://`. To confirm a specific change landed, `grep` the deployed file over SSH rather than exercising a live endpoint.

## Gotchas

- **Routing**: a request returning the portfolio HTML instead of JSON means it's not resolving under `shivarya.dev/clear_call` — confirm the deploy dir is the docroot subfolder, not `~/public_html/` root.
- **Inode quota**: this cPanel account has hit its file-count (inode) limit before. If a write fails with "Disk quota exceeded", check `uapi Quota get_quota_info` — it's inodes, not MB. The `google/apiclient` vendor tree is large; `composer install --no-dev` + pruning unused Google service classes helps (per diet-plan deploy notes). Don't delete other projects' files without asking.
- **cron**: ClearCall has no cron job (ring lifecycle is lazy-reaped per request via `reapStaleRinging()`); don't add one.
