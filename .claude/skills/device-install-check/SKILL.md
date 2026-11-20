---
name: device-install-check
description: Install the DanceSonic debug APK on the attached Android device with adb, launch it, and read what is on screen. Use when asked to install, run, or check the app on the phone, or to see why a screen or button behaves as it does.
---

# Install and check on the device

## Find the device

```bash
ADB=~/Android/Sdk/platform-tools/adb
$ADB devices -l
```

The usual device is a Pixel 6a with serial `25291JEGR03051`. If the serial differs, use the one listed. Pass it to every command with `-s <serial>`.

If the list is empty, stop and tell the user to connect the phone and allow USB debugging. Do not run `install` or `shell` in that case: adb waits for a device with no time limit, and the command then runs until its timeout. (This happened on a session; the install command had to be stopped.)

## Install and launch

Build first (see the `android-build-test` skill), then:

```bash
$ADB -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s $SERIAL shell monkey -p org.dancesonic.player -c android.intent.category.LAUNCHER 1
```

`-r` keeps the app's data (the saved server connection and the catalog).

## Read the screen

The screen must be awake and unlocked first. Check:

```bash
$ADB -s $SERIAL shell dumpsys power | grep -m1 mWakefulness   # Awake, Dozing, Asleep
$ADB -s $SERIAL shell dumpsys window | grep -m1 mCurrentFocus  # should show org.dancesonic.player
```

If the device is dozing, wake it with `input keyevent KEYCODE_WAKEUP`. A lock screen cannot be bypassed; ask the user to unlock.

A locked phone shows `mCurrentFocus=...NotificationShade` or `mDreamingLockscreen=true`. The app is not visible in that state. Installing still works while locked, so install first and ask the user to unlock afterwards.

Then dump the UI and print the visible text:

```bash
$ADB -s $SERIAL shell uiautomator dump /sdcard/ui.xml
$ADB -s $SERIAL shell 'cat /sdcard/ui.xml' | grep -o 'text="[^"]*"' | grep -v 'text=""'
```

The dump lists only the nodes on screen. Text below the fold, or behind a scrolled list, is missing from it.

For a visual check, take a screenshot into the session scratchpad and open it with the Read tool, which shows the image:

```bash
$ADB -s $SERIAL exec-out screencap -p > "$SCRATCHPAD/screen.png"
```

Replace `$SCRATCHPAD` with the session scratchpad path given in the environment. Do not write screenshots to `/tmp` directly.

Compose buttons report `enabled="true"` on their label nodes, so a disabled button is hard to see in the dump. Tap it and check whether anything changes.

## Scroll

Scroll with a slow swipe, then take a new dump or screenshot. The text does not change if the swipe was too fast:

```bash
$ADB -s $SERIAL shell input swipe 540 1500 540 500 600   # up 1000 px, 600 ms
```

Repeat once per screen. Check the screenshot, not the dump, to confirm the scroll moved.

## Known screens

- Config page (the "Config" text button at the top right of the Library screen): content order is App, Subsonic connection, Library, Re-enter connection, Playback, Offline, DanceBrainz. The player bar sits over the bottom, so the last rows need an extra scroll.
- Library, Dance and Playlists are the bottom tabs. The Library artist list is the start screen.
- Track detail: the Info button on a library track row opens it as a dialog (cover, facts, then the Dance, Meter, Description and Shoes rows). It has Play and Close, not transport buttons.
- Now-playing sheet: tap the title in the player bar to open it. It fills the screen and has Prev, Play/Pause and Next under the seek bar. It shows "Nothing playing" when no track is loaded, so the transport row needs a track playing before it can be checked.
- Opening a track's detail or the sheet changes nothing on DanceBrainz. The Dance, Meter and Shoes rows do change it: check them only when the user asks for that action, because they send real edits.

## Tap, and read errors

- Tap by coordinates from the `bounds="[x1,y1][x2,y2]"` attribute: the centre is `((x1+x2)/2, (y1+y2)/2)`. Use `input tap X Y`.
- The app does not log its requests or errors. Crashes show up in logcat:
  `$ADB -s $SERIAL logcat -d -t 400 | grep -E 'FATAL|AndroidRuntime'`

## Safety

- Do not change device settings, accounts or other apps.
- Tapping in the app is fine only when the user has asked to check that action.
- Never paste the API key, the pairing token or the password into logs, commands or replies.
