# mpv-android-dufs

[![Build Production](https://github.com/dennyak47/mpv-android-dufs/actions/workflows/build.yml/badge.svg)](https://github.com/dennyak47/mpv-android-dufs/actions/workflows/build.yml)

## Features

- Browse media files from HTTP or HTTPS DUFS servers directly in the app
- Add, save, switch between, and remove multiple DUFS servers
- Navigate nested directories with back, refresh, and retry actions
- Filter directory entries to show folders and supported media files
- Distinguish between folders, videos, audio files, and other playable media
- Generate thumbnails for remote videos with FFmpeg
- Cache thumbnails in memory and on disk to reduce repeated remote reads
- Remember the most recently played DUFS video for quick access
- Save the playback position when leaving the player and restore it later
- Use a Material 3 DUFS browser interface with light and dark themes
- Build and verify signed production APKs and AABs with GitHub Actions

## Downloads

Published builds are available from this repository's
[Releases](https://github.com/dennyak47/mpv-android-dufs/releases)
page.

Maintainers can also run the
[`Build Production`](https://github.com/dennyak47/mpv-android-dufs/actions/workflows/build.yml)
workflow and download these artifacts:

- `mpv-dufs-universal-release`: Universal release APK
- `mpv-dufs-abi-release`: ARM-specific release APKs
- `mpv-dufs-release-aab`: Release AABs for app distribution

## Production builds

The `Build Production` workflow is started manually. It builds and verifies
signed release APKs and AABs without producing debug packages.
