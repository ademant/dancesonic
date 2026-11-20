---
name: android-build-test
description: Build and unit-test the DanceSonic Android app (Gradle wrapper, JDK 21, Android SDK at ~/Android/Sdk). Use when asked to build, compile, run tests, or verify a change in this repo before reporting it done.
---

# Build and test DanceSonic

Environment (this machine; nothing is on PATH by default):

```bash
export JAVA_HOME=$HOME/.local/opt/jdk-21.0.13+9
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
```

`local.properties` already points `sdk.dir` at the SDK. Do not commit it.

## Commands

- Format first, so the checks see reflowed code: `./gradlew --no-daemon -q ktlintFormat`
  Run it before the checks. Reformatting changes exact text, so re-read a file before editing it again.
  `ktlintFormat` exits with an error when it finds violations it cannot fix itself (for example, import order or a file name). The error does not mean the format pass failed to run. Fix the listed items, then run `ktlintCheck` to confirm.
- Shell variables are not set between tool calls. Use an absolute path for logs, for example the session's scratchpad directory, not `$SCRATCHPAD`. Writing to `/` or to a variable that is empty fails with "Keine Berechtigung" (permission denied).
- The usual check before reporting work done (all must pass):
  `./gradlew --no-daemon -q testDebugUnitTest ktlintCheck detekt assembleDebug`
- Single test class: add `--tests 'org.dancesonic.player.ui.ConnectViewModelTest'`.
- Compile only (fast feedback while editing): `./gradlew --no-daemon :app:compileDebugKotlin`
- ktlint and detekt are configured. Run `ktlintCheck` and `detekt` as above; AGENTS.md requires both.
- Write logs to the session scratchpad, not `/tmp`, for example `> "$SCRATCHPAD/build.log" 2>&1`.

Commands take 1 to 3 minutes; the full set with a clean build can take 2 to 3 minutes. Run them with a timeout of at least 590000 ms. Gradle prints `BUILD FAILED` with the failing task; the details are in the log.

## Reading results

Gradle's summary hides counts for passing runs. Count from the XML reports:

```bash
python3 - <<'PY'
import glob, re
t = f = e = 0
for x in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(x).read())
    t += int(m[1]); f += int(m[2]); e += int(m[3])
print("tests", t, "failures", f, "errors", e)
PY
```

## Known pitfalls

- Kotlin compile errors cascade (for example, a missing `rememberSaveable` import causes "unresolved 'it'" errors). Fix the first error and rebuild.
- `runBlocking { ... }` as the last expression of a test returns a value, which JUnit rejects. Use `runBlocking<Unit> { ... }`.
- MockWebServer 4.x uses `requestUrl` and `MockResponse().setBody(...)`. The `Builder` API is OkHttp 5.
- AGP 9 needs `android.builtInKotlin=false` and `android.newDsl=false` in `gradle.properties`. Do not remove them.
- Room queries must not run on the main thread. Tests use `allowMainThreadQueries()` on the in-memory test database only.
- Detekt fails the build, not only the report. Its rules, as set here:
  - `LongParameterList`: observed: a constructor with 7 parameters is reported ("threshold is set to 7"). Keep constructors at 6 or fewer. Group values into a class (for example `TrackEntries` and `TrackEntryActions` for the detail rows), an interface with default methods (`PlaybackEvents` for the player's callbacks), or pass the owner instead of its fields.
  - `TooManyFunctions`: observed: a class with 15 functions is reported, so keep a class at 14 or fewer (private functions count). An interface with 11 is reported, so keep an interface at 10. Move helpers to a separate file, a separate class, or extension functions. A DAO counts as an interface. A Retrofit service interface counts too: a new endpoint may need its own small service class.
  - `LoopWithTooManyJumpStatements`: one `break` or `continue` per loop. Use a sequence with `filter` and `mapNotNull` instead of several jumps.
  - `MatchingDeclarationName`: a file that holds one class is named after it. Rename the file, not the class.
  - `MaxLineLength` and `MagicNumber`: wrap long lines; name numbers as `const val`.
- Media3 `SimpleCache` allows one instance per folder. Robolectric builds the app object (and its container) for every test, so do not open the cache in a container's constructor. Create it lazily, and never create it twice in one test.
- Compose: `Modifier.weight` is a `RowScope` member, so it needs no import. An import for it fails with "it is internal".
- Tests that use `Locale`-dependent formatting: a German phone formats `2.6` as `2,6`. Pin the locale in the test (`Locale.setDefault(Locale.US)`) and restore it afterwards.
- Known flake: `ConnectViewModelTest` ("disconnect ... clears the catalog") can read the catalog before the background clear finishes. It failed once in a full run and passed on re-runs. Re-run before blaming a change; if it fails again, fix the test to wait for the clear.
- `PlaylistViewModelTest` (uncaught exceptions before test) is also a known flake; re-run once.
- `ControllerPolicyTest` (8 cases) fails on `main` because it needs Robolectric. It is not a regression from a change to playback code.
- Known detekt failures on `main` (not caused by a branch): `ConnectViewModel` has 18 functions (class limit 15), and `ControllerPolicy.kt` line 10 is longer than the maximum line length. A branch that does not touch these files is not blocked by them, but the PR body must say that `detekt` still fails for these two reasons.
