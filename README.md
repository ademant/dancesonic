# DanceSonic

DanceSonic is a small, secure Android music player for dance music. It streams from a
Subsonic-compatible server (Navidrome, or folksonic/tinysonic) and enriches tracks with dance
metadata from [DanceBrainz](https://dancebrainz.balfolk.social/).

Playback works without DanceBrainz. Metadata is enrichment: a track without a MusicBrainz ID, or
without a DanceBrainz match, stays playable and visible in the library.

## Features

- **Library**: browse artists, albums and tracks from the server, with a local catalog cache.
- **Dance view**: tracks grouped by dance type.
- **Playlists**: course playlists kept on the phone.
- **Track detail**: from a track's Info button or the now-playing sheet, the track's facts
  (cover, title, release, artist, length, tempo), then its DanceBrainz entries:
  - **Dance**: tap to remove assigned dances or add new ones.
  - **Meter**: tap to set a meter and an optional sub-meter, shown as `8/4 - 3+3+2`.
  - **Dance description**: shown only when DanceBrainz has one; tap to read it.
  - **Shoes**: five shoes; tap one to rate the track. The public median is shown when there is one.
- **Offline edits**: DanceBrainz changes are applied on the phone at once, queued, and sent when
  the server is reachable. Held or conflicting changes stay visible until dismissed.
- **Background playback** with lock-screen and notification controls, tempo change, and chapter
  marks from DanceBrainz.

## Connecting to a server

Sign-in is done with one of three methods:

- **QR code** shown on the server's account page.
- **Username and password**. The password is used once to create an API key, then discarded. It is
  never stored or logged.
- **API key** typed in.

Connections use HTTPS. Plain `http://` is accepted only for private network addresses (localhost,
RFC 1918 ranges, IPv6 unique-local and link-local), so a home server works without a certificate.
Be aware that on such a connection the API key travels unencrypted.

DanceBrainz is signed in separately, by pairing the phone as a device on the DanceBrainz account
page. The phone keeps a device key in the Android Keystore and proves possession of it with DPoP
proofs.

## Security

- The Subsonic API key and the DanceBrainz credentials are stored encrypted in app-private
  storage, with a key held in the Android Keystore (AES-GCM).
- Cleartext traffic is disabled in the network security config.
- Backups are disabled, so credentials and the database do not leave the device.
- Credential types redact themselves in `toString()`, so keys, tokens and passwords do not reach logs or debug output.
- Only the permissions needed for network access and foreground media playback are requested:
  `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

## Requirements

- Android 10 or later (minSdk 29).
- JDK 21.
- Android SDK with the compile SDK listed in `app/build.gradle.kts`.

## Build and test

```sh
./gradlew assembleDebug            # build the debug APK
./gradlew testDebugUnitTest        # unit tests (JUnit, MockWebServer, Robolectric Compose tests)
./gradlew ktlintCheck detekt       # style and static analysis; both must pass before a PR
./gradlew ktlintFormat             # apply ktlint's formatting
```

Create `local.properties` with `sdk.dir=/path/to/Android/Sdk` if Android Studio has not already.
Do not commit it.

## Project layout

```
app/src/main/java/org/dancesonic/player/
  subsonic/    Subsonic API client, URL policy, sign-in
  dance/       DanceBrainz clients, device pairing, meter and dance rules
  data/        repositories, offline edit queue, Room database and migrations
  playback/    Media3 playback service, tempo, audio focus
  ui/          Jetpack Compose screens and view models
app/schemas/   exported Room schemas (committed with each migration)
```

The database uses Room with explicit migrations; `fallbackToDestructiveMigration` is not used,
so playlists and cached metadata survive upgrades.

## Contributing

- Work on a feature branch and open a pull request against `main`.
- Reference the issue the PR addresses (for example `Refs #75`).
- Every bug fix ships with a regression test.
- Coding conventions and the rules for Subsonic, Room and security are in [AGENTS.md](AGENTS.md).

## Licence

See [LICENSE](LICENSE).
