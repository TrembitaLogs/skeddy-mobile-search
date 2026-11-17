# Search App Release Runbook

## How it fits together

- The search app is distributed as a signed APK, not through Google Play.
- The backend does not store per-release metadata. It holds:
  - `SEARCH_APP_UPDATE_URL` — a fixed URL to the newest APK
    (`.../search/skeddy-search-latest.apk`);
  - `min_search_app_version` — the minimum accepted client version.
- The search app sends `app_version` (from `BuildConfig.VERSION_NAME`) on every ping.
- If that version is lower than the minimum, the backend:
  - returns `force_update=true` and `update_url` in the ping response;
  - sets the ping interval to `PING_INTERVAL_FORCE_UPDATE` (300 seconds);
  - sends a `SEARCH_UPDATE_REQUIRED` push to the user's main app
    (at most once per hour per user).
- The search app reacts with a blocking `ForceUpdateActivity`; its Update button opens
  the URL in a browser, and the user installs the APK manually.
- The main app shows "device update required" in the dashboard while
  `/search/status` reports `force_update=true`.
- Publishing an APK does **not** notify users by itself. To roll an update out,
  raise `min_search_app_version` (see [Force update rollout](#force-update-rollout)).

## Release checklist

1. Open a PR against `main` and wait for `CI Checks / build-and-test`.
   - `main` is protected: PR required, `build-and-test` required, admins enforced.
     Direct pushes are blocked.
   - Optional: run the `Pre-release check` workflow manually (release unit tests).
2. Merge the PR.
3. Tag the merged `main` commit and push the tag:

   ```bash
   git checkout main
   git pull --ff-only
   git tag -l 'v*' | wc -l   # current tag count; the new tag makes it N + 1
   git tag v1.0.5
   git push origin v1.0.5
   ```

4. The `Release` workflow (`.github/workflows/release.yml`) runs on the tag and:
   - derives `versionName` from the tag (without `v`);
   - sets `versionCode` to the number of `v*` tags **including the new tag**;
   - decodes the signing keystore, runs `testReleaseUnitTest`, and builds the signed APK;
   - uploads two objects to Contabo Object Storage:
     - `search/skeddy-search-latest.apk` (short cache, fixed URL);
     - `search/skeddy-search-v1.0.5.apk` (immutable);
   - creates a GitHub Release with the APK attached.
5. Verify the public URLs (see [Verification](#verification)).
6. Install the APK on a test device.
7. If this release must be mandatory, raise the minimum version
   (see [Force update rollout](#force-update-rollout)).

## Versioning rules

- Use plain semantic versions: `vX.Y.Z` (for example `v1.0.5`). The backend compares
  versions with PEP 440 (`packaging.version.Version`), so avoid suffixes such as
  `-rc1` or `-beta`.
- Create release tags only on commits that are already merged into `main`.
- Never create extra `v*` tags for testing. `versionCode` is derived from the total
  number of `v*` tags, so an extra tag permanently shifts it.
- Never delete or reuse a tag. Deleting a tag can produce a duplicate `versionCode`,
  and reusing one misrepresents what was released.
- If a release is broken, publish a new tag (for example `v1.0.6`). Do not rewrite
  the bad one.
- `versionCode` must increase for Android to accept an upgrade. The tag-count scheme
  relies on tags only ever being added.
- Never commit the signing keystore. It is injected from the
  `RELEASE_KEYSTORE_BASE64` secret.

## Force update rollout

The backend decides who must update. There is no per-release registration step.

### Via the admin panel (preferred)

1. Open `https://admin.beta.skeddy.net/admin` → **App Config**.
2. Edit `min_search_app_version` and set it to the new version (for example `1.0.5`).
3. Save. `AppConfigAdmin.after_model_change()` immediately removes the value from
   Redis and the in-process cache.

### Via SQL (if the admin panel is unavailable)

```sql
INSERT INTO app_configs (key, value)
VALUES ('min_search_app_version', '1.0.5')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
```

Then invalidate the shared cache:

```bash
set -a; . ./.env; set +a
docker exec skeddy-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning \
  DEL app_config:min_search_app_version
```

Otherwise the cached value can live for up to 5 minutes in Redis and up to
10 minutes in the per-worker memory fallback.

### Environment fallback

`MIN_SEARCH_APP_VERSION` in the server `.env` is used only when no `app_configs`
row exists. Once the row exists, changing the environment variable has no effect.
Changing `.env` also requires a backend restart.

### What happens after the change

- On its next ping, an outdated search app receives `force_update=true`,
  `update_url`, and a 300-second interval.
- The search app shows the blocking update screen.
- The main app shows "device update required" and may receive a
  `SEARCH_UPDATE_REQUIRED` push (at most once per hour per user).
- After the user installs a version >= the minimum and pings again, the block
  clears automatically.

### Rollback

Lower `min_search_app_version` in the admin panel. Affected clients clear the block
on their next ping.

## Verification

Check the published APK:

```bash
curl -sI 'https://usc1.contabostorage.com/24e442a8a38a481386fad53f3ff02216:skeddy-search-releases/search/skeddy-search-latest.apk' \
  | grep -iE 'HTTP|content-type|content-length'
```

Expected: `HTTP/1.1 200 OK`,
`content-type: application/vnd.android.package-archive`, and the new file size.
The versioned object is at
`.../search/skeddy-search-v1.0.5.apk`.

Check the current minimum version on the backend host:

```bash
docker exec skeddy-db psql -U skeddy -d skeddy -tAc \
  "select value from app_configs where key = 'min_search_app_version';"
set -a; . ./.env; set +a
docker exec skeddy-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning \
  GET app_config:min_search_app_version
```

Check the latest release tag and the next `versionCode`:

```bash
git tag -l 'v*' | sort -V | tail -1
git tag -l 'v*' | wc -l
```

## Troubleshooting

**The `Release` workflow fails**
- Check the Actions logs. Build or test failures are code issues.
- Keystore problems mean `RELEASE_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  or `KEY_PASSWORD` is wrong.
- Contabo may return `429 Too Many Requests`; re-run the failed jobs for the same tag.

**`versionCode` did not increase**
- Someone added or deleted a `v*` tag. Count tags before tagging and never create
  test tags.

**Clients do not see the update**
- Confirm `min_search_app_version` is higher than the client's `app_version`.
- Confirm the search device is online and actually pinging.
- Check the DB value and the Redis key. If the value was changed directly in SQL and
  Redis was not cleared, wait up to 5 minutes or delete the key.
- Confirm `latest.apk` returns the new size (cache-control is 5 minutes).

**The APK is published but nobody is forced to update**
- This is expected. Publishing does not notify users. Raise the minimum version when
  you want a mandatory rollout.

**A release is bad**
- Publish a new, higher tag. Do not delete or reuse the bad tag.
- If the bad release is currently mandatory, lower `min_search_app_version` while
  you prepare the fix.

## Required GitHub secrets

- `CONTABO_S3_ACCESS_KEY_ID`
- `CONTABO_S3_SECRET_ACCESS_KEY`
- `RELEASE_KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The Contabo credentials are account-wide; treat them as production secrets.

## Backend reference

- `backend/app/config.py` — `SEARCH_APP_UPDATE_URL`, `MIN_SEARCH_APP_VERSION`,
  `PING_INTERVAL_FORCE_UPDATE` (300 seconds).
- `backend/app/services/config_service/search.py` — resolution order Redis → DB →
  settings; `set_min_search_version()` helper (currently not exposed as an API).
- `backend/app/admin/views.py` — `AppConfigAdmin` and cache invalidation on save.
- `backend/app/routers/ping.py` — force-update response.
- `backend/app/routers/search.py` — `force_update` in `/search/status` for the main app.
- `backend/app/services/ping_service/orchestration.py` — hourly push notification.
- `search/app/src/main/java/com/skeddy/service/MonitoringForegroundService.kt` — sends
  `app_version`, handles `force_update`.
- `search/app/src/main/java/com/skeddy/ui/ForceUpdateActivity.kt` — blocking update screen.
- `main/app/src/main/java/com/skeddy/main/ui/dashboard/components/DeviceStatusCard.kt` —
  "device update required" state.

## Distribution notes

- There is no Google Play release step; the APK is sideloaded from the public Contabo URL.
- The public URL includes the Contabo tenant ID and must not change per release. If it
  ever changes, update the server `.env`, `backend/app/config.py`, and
  `backend/.env.example`, then deploy the backend.
- The `latest` object is overwritten on every release; versioned objects are immutable.
