Release Process
===============

1. Edit build.gradle, increase versionCode, set versionName to `YYYY-MM-DD-release`, commit
2. Clone this commit to a clean working folder
3. Go through buildscripts setup steps
4. Build libmpv for all architectures
5. Build app via `./buildall.sh --clean -n`

   Make sure to set `ANDROID_SIGNING_KEY=... ANDROID_SIGNING_ALIAS=mpv-android-release` before.
   If the keystore is password protected also set `ANDROID_SIGNING_KEY_PASSWORD=...`
   and `ANDROID_SIGNING_ALIAS_PASSWORD=...`.
6. Install APK on devices you have on hand, **verify that core features work as expected**
7. Run `docs/prepare_artifacts.sh`
8. Create GitHub release with tag name `YYYY-MM-DD`, upload relevant APKs (don't publish yet)
9. Run `docs/print_releasenotes.sh`, copy into release notes
10. Write a changelog (to be put in GH *and* Play Store)
11. Create release in [Google Play Console](https://play.google.com/apps/publish/), upload app bundle and symbol ZIP

    If any checks fail for the release they will have to be fixed first, restarting the process.
12. Push commit from step 1
13. Publish GitHub release & tag
14. Start rollout on Google Play

Should it be necessary to release more than once a day subsequent releases are suffixed like `2021-04-12-2`.

## GitHub Actions release workflow

The repository includes `.github/workflows/release.yml` for automated signed builds.

### Required repository secrets

* `ANDROID_SIGNING_KEY_B64` - base64 encoded keystore file
* `ANDROID_SIGNING_ALIAS` - keystore alias used for signing the app bundle
* `ANDROID_SIGNING_KEY_PASSWORD` - keystore password
* `ANDROID_SIGNING_ALIAS_PASSWORD` - key password (or the same value as the keystore password)

To publish directly to Google Play from the workflow also add:

* `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` - Play Console service account JSON

### Workflow behavior

* Push a tag to build signed release APKs, the signed app bundle, symbol archives, and publish a GitHub release.
* Run the workflow manually to build the same artifacts for an explicit tag, optionally skipping the GitHub release or uploading the bundle to a selected Google Play track.
