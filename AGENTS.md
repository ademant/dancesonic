# AGENTS.md

Guidance for AI coding agents working in this repository. DanceSonic is a minimal, secure Android music player (Kotlin) that streams from a Subsonic-compatible server (Navidrome first) and enriches tracks with DanceBrainz metadata. The MVP is tracked in issue #1.

Code creation must be state of the art for **Kotlin**, the **Subsonic API**, and **SQLite (via Room)**. Prefer current, idiomatic, maintained APIs over older patterns, and say so when you choose an older one for a concrete reason.

## General principles

- Target **Android 10+ (minSdk 29)** and the latest stable compileSdk/targetSdk. Use the latest stable Kotlin 2.x and AGP/Gradle versions available when you start, pinned in a version catalog (`gradle/libs.versions.toml`).
- Build with **Gradle Kotlin DSL** and a version catalog. Do not hard-code dependency versions in module build files.
- Keep the footprint small: fewer dependencies, no unused libraries, no large frameworks for simple problems. Each new dependency needs a one-line justification in the PR description.
- Prefer clear, boring code over clever code. Small, focused classes; explicit names; no `!!` (non-null assertion) outside tests; no swallowed exceptions.
- Do not add features beyond the issue's scope. Out of scope for v1: other providers than Subsonic, social features, cloud playlist sync, elaborate library management.

## Kotlin

- Use **Kotlin coroutines and Flow** for all asynchronous work. Use structured concurrency: scope work to a `ViewModel`/service lifecycle, never `GlobalScope`.
- Inject dispatchers (`CoroutineDispatcher` parameters, defaulting to `Dispatchers.IO` / `Default` as appropriate) so tests can substitute them.
- Model results and errors explicitly, for example with a sealed `Result`-style type (`Loading`, `Success`, `Error`), rather than throwing across layers. Catch `CancellationException` and rethrow it.
- Use `data class` for immutable models, `sealed interface` for closed hierarchies, `value class` for typed IDs (e.g. `SongId`, `Mbid`).
- Use **kotlinx.serialization** for JSON. Make DTO fields nullable where the server may omit them; map DTOs to domain models at the repository boundary.
- UI: **Jetpack Compose** with Material 3, state hoisted into `ViewModel`s exposing `StateFlow`. Use `collectAsStateWithLifecycle`.
- Dependency injection: Hilt, or manual constructor injection if the graph stays small. Be consistent within the project.
- Avoid Android `Context` leaks; pass `Application` context only where required.

## Subsonic API

- Target the **Subsonic REST API v1.16.x** and the **OpenSubsonic** extensions that Navidrome supports. Check what the server advertises (`getOpenSubsonicExtensions`, `ping`) rather than assuming a feature exists. Do not hard-code assumptions about the server version.
- Always request JSON: `f=json`. Send the client name `c=DanceSonic` and an API version `v` that matches what you actually implement.
- **Authentication: API key only for `/rest` calls, and for folksonic's `GET /api/analysis`.** The app authenticates Subsonic requests with an OpenSubsonic API key (`apikeyauth` extension). The analysis endpoint takes the same key, sent in the `X-Api-Key` header, never in the URL; it is used only when the server advertises `tinysonicAnalysis`. Call `getOpenSubsonicExtensions` at connect time and refuse to continue with a clear error if `apikeyauth` is not advertised. Do not implement token auth (`u`/`t`/`s`) or the legacy plaintext `p=` parameter.
- **Obtaining a key (issue #3).** Three ways in: a pairing QR code, a username and password, or a key typed in. A password is used only for the sign-in exchange (session token, then `POST /api/keys`, then the session is revoked). The password and session token are never stored or logged. The password exchange and QR pairing target folksonic/tinysonic `/api/*` endpoints, which Navidrome does not offer.
- **Only HTTPS.** Enforce this in the network layer (reject `http://` base URLs) and in the Android network security config (`cleartextTrafficPermitted="false"`).
- Build the HTTP stack on **Retrofit + OkHttp** with kotlinx.serialization (or Ktor Client if the team prefers; pick one and stay with it).
- Required endpoints for the MVP typically include `ping`, `getArtists`/`getIndexes`, `getArtist`, `getAlbum`, `getSong`, `getPlaylists`, `getPlaylist`, `createPlaylist`, `updatePlaylist`, `search3`, `getCoverArt`, and `stream`. Verify each against the server's actual response shape; Subsonic JSON wraps everything in a `subsonic-response` object and reports errors with a `status` of `failed` and an `error` object, which must be handled.
- **MBID matching:** the recording MusicBrainz ID is the primary key for DanceBrainz matching. Read it from the server's song metadata (the OpenSubsonic `musicBrainzId` field where present) and treat it as optional. Tracks without an MBID or without a DanceBrainz match must stay playable and visible in the Library.
- Streaming: `stream` URLs carry the API key in the query string. Treat any URL built this way as a secret.
- Be a good client: cache catalog responses, avoid refetching the whole library, paginate or page through large collections, and use reasonable timeouts and retry with backoff for idempotent requests only.

## Security and secrets

- Store the **API key** in **app-private** storage, encrypted with a key in the **Android Keystore** (AES-GCM), for example through DataStore. Do not use plaintext SharedPreferences or files. `EncryptedSharedPreferences` is deprecated. Never store the user's Navidrome password.
- Provide a way to remove the stored key (logout) that deletes it from storage and clears the cached catalog that belongs to that server.
- Set `android:allowBackup="false"` (or explicit backup/data-extraction rules that exclude credentials and the database if they should not leave the device).
- **Never log API keys or full stream/API URLs.** Install an OkHttp logging interceptor only in debug builds and redact the API key query parameter. Do not log response bodies that could contain user data.
- Do not expose credentials through intents, notifications, or exported components. Keep components `exported="false"` unless required.
- Validate server-supplied strings before using them in file paths or SQL. Use parameterized queries only.
- Follow least-privilege permissions. Only request `INTERNET` and, for background playback, what Media3 requires (e.g. the foreground service permission for media playback).

## Playback

- Use **AndroidX Media3** (ExoPlayer successor), not the deprecated standalone ExoPlayer distribution.
- Run playback in a `MediaSessionService` so audio continues in the background and shows a media notification. Expose a `MediaSession` for lock screen and system controls.
- Stream through Media3's OkHttp data source so the same authenticated client is used.
- **Tempo:** change speed with `Player.setPlaybackParameters(PlaybackParameters(speed, pitch = 1f))`. Keep pitch constant unless the user asks otherwise; Media3 uses pitch-preserving time-stretching.
- Handle audio focus, headphone unplug, and network loss explicitly.

## SQLite and Room

- Use **Room** (on top of SQLite) with **KSP** for annotation processing. Do not use raw `SQLiteOpenHelper` unless there is a clear reason.
- Enable **foreign keys** and declare them in the schema. Index every foreign key and every column used in a `WHERE`, `JOIN`, or `ORDER BY` in hot paths.
- DAOs: use `suspend` functions for writes and `Flow` for observed queries. Use `@Transaction` for multi-statement changes. Use `@Upsert` / `OnConflictStrategy` intentionally.
- Never run queries on the main thread. Room enforces this; do not work around it with `allowMainThreadQueries()`.
- **Migrations:** export schemas (`room.schemaLocation`) and commit them. Write explicit `Migration` objects and test them with `MigrationTestHelper`. Never use `fallbackToDestructiveMigration()` for user data (playlists and cached metadata must survive app restarts).
- Store MBIDs and server IDs as indexed `TEXT` columns. Store timestamps as epoch milliseconds (`INTEGER`).
- Use WAL journaling (Room's default on Android) and keep transactions short.
- Keep the **audio cache** separate from the database. Store audio files on disk with a **bounded LRU** policy and a configurable size cap; the catalog and metadata live in Room.
- Provide a "refresh cache" path that re-syncs catalog and DanceBrainz metadata without deleting user playlists.

## DanceBrainz metadata

- Fetch DanceBrainz data by MBID and cache it in Room. Treat it as enrichment: failures must not block playback or the Library.
- Keep the dance-type model in one place so the Dance view and playlist grouping use the same definitions.

## Testing

- **Unit tests** (JUnit 5 or JUnit 4 consistently): repositories, mappers, API key request construction, tempo and playlist logic.
- **Network tests:** use **MockWebServer** to test Subsonic responses, including `status: failed` error payloads, missing optional fields, rejection of servers without `apikeyauth`, and API key handling.
- **Flow tests:** use Turbine.
- **Database tests:** Room in-memory tests for DAOs, and `MigrationTestHelper` for every schema version change.
- **UI tests:** Compose UI tests for the Library, Dance, and Playlist screens.
- Every bug fix ships with a regression test. Do not disable or delete failing tests to get a green build.

## Code quality

- Format with **ktlint** (or the Kotlin official style via Spotless) and lint with **detekt**. Both must pass before a PR is ready.
- Run `./gradlew build` and the relevant tests before reporting work as done. If you cannot run them, say so explicitly.
- Public APIs get KDoc. Internal logic gets comments only where the "why" is not obvious.
- Keep `Log` calls at debug level and free of secrets (see Security).

## Git and pull requests

- Work on a feature branch, never directly on `main`.
- Keep PRs small and focused. Reference the issue they address (e.g. `Refs #1`).
- A PR description should state what changed, why, how it was tested, and any security or migration impact.
- Do not commit generated files, local `local.properties`, keystores, or signing secrets.
