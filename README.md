# NOISE for Android

An Android reimagining of the noisedoors port (Ixar's WPF fan project: the NOISE entity from
Doors, who chases your cursor). On a phone there is no mouse to chase, so the idea is rebuilt
around what Android *can* do: draw over other apps, own the touch screen, vibrate, live in Quick
Settings.

## How it plays

1. Tap **Start NOISE** (a foreground service; one persistent notification). At random intervals
   (Min/Max delay) a **TV** pops up at the bottom of whatever app you're in and Noise starts
   emerging from it (8.7 s: your cue). You can drag the TV sideways, like the original popup.
2. When Noise is out it **takes over the screen** (a transparent full-screen overlay that
   swallows touches). **Your finger is the cursor**: Noise replays your finger's path ~200 ms
   late. It only moves while you do: **hold still or lift your finger to freeze it**. If it gets
   within 32 dp of your finger, you get the jumpscare.
3. Escape: **Fire alarms** are hidden on screen and fade in as your finger nears them; tap one to
   pull it. With *Fire alarm amount* > 1, **all alarms are on screen at once** and each pulled one
   disappears for good; pulling the last one banishes Noise.
4. **Hijack (RANS0M):** tap the TV while Noise is out (or use *Feed the TV*, or share a file to
   the app) and pick a known RANS0M executable; Noise flies home, the TV plays the emerge in
   reverse, and a slower, stuttering hijacked Noise comes out. Dying or pulling the last alarm
   cures it. The hijacked Noise **stays paused until you first touch or move**, so the round
   starts when you decide to. (Same SHA-256 check as the original; list in
   `assets/ransom-hashes.txt`.)

| Desktop original | Android |
|---|---|
| Mouse cursor | Finger on the takeover overlay (stays where you last touched) |
| Key presses echoed by Noise | Taps echoed back as a short vibration |
| Click-through overlay windows | `TYPE_APPLICATION_OVERLAY`; the takeover is only attached while Noise is actually out, so the phone is usable during the emerge and after the round |
| Tray menu | Foreground-service notification (*Call Noise* / *Stop*) + **Quick Settings tile** |
| Drag .exe onto the TV | Tap the TV / *Feed the TV* / Share-to-NOISE, then hash check |
| Shutdown / custom command on death | Opt-in: **go to home screen** or **lock the screen** (device admin, force-lock only). An app can't power off a phone |
| Multi-screen | Single screen |
| Test mode | Same (skips the death consequences; adds a *Hijack now* button) |

Phone-only additions: vibration (heartbeat buzz while Noise sprints, jumpscare shake), **round
timeout** (default 120 s: Noise loses interest), no spawns while the screen is off or locked,
optional start on boot.

## Battery
Between rounds the app does essentially nothing: one sleeping timer (it doesn't wake the phone
from deep sleep), no wakelocks, no polling, overlays detached, audio players released. Power is
only spent while a round is running, so the main levers are fewer / shorter rounds (raise
*Min delay*, lower *Round timeout*). Per round the game now:
- ticks at ~60 Hz only while Noise is actually chasing, ~20 Hz when it's frozen, 10 Hz while the TV
  emerges and 4 Hz during fullscreen scenes (it used to tick at 100 Hz the whole time);
- redraws the full-screen overlay only when something moved or faded (not every tick);
- frees all 16 audio decoders when the round ends;
- under Android's **Battery Saver**: ~30 Hz chase ticks and no vibration.

**Touch pass-through at the end of a scene:** once more than half of a fullscreen scene is
transparent for good, the overlay stops swallowing touches and drops to 79% opacity, so you can use
your phone again while the last frames fade out (measured from the frames: jumpscare from 8.2 s of
9.84 s, hijack jumpscare from 6.8 s of 9.84 s, fire alarm from 13.7 s of 22 s). 79% because
Android 12+ only lets touches pass through overlays that are at most 80% opaque.

**Lock the screen (opt-in):** overlays can't draw over the lock screen, so this uses a small
show-when-locked activity (`LockScreenActivity`).
- *Normal death:* while the jumpscare shows PAUSE (from 4.7 s), the phone is locked. The next time
  you switch it on, the rest of the scene (PLAY, then the fade-out: `jumpscare_tail.webp`, from
  5.92 s) plays directly over the lock screen, with the death audio resumed in sync, then goes away.
- *Hijack death:* the phone is locked right after the jumpscare ends (no tail).
- If the activity can't start, the phone is simply locked when the jumpscare ends.
Needs the screen-lock (device admin) permission; test mode skips it.

## Safety valves (the takeover swallows touches, so there are several ways out)
Home/back gestures still work; the notification's **Stop** works (pull the shade down); the round
timeout ends the round; the takeover is never attached outside a round.

## Build
Open this folder in Android Studio (Ladybug or newer, JDK 17), or:
```
gradle wrapper --gradle-version 8.10.2   # once, to create ./gradlew
./gradlew assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
```
`.github/workflows/android.yml` builds a debug APK in GitHub Actions (move it to your repo's
root `.github/` if this folder isn't the repo root). minSdk 28, target 35.

First run: *Allow display over other apps*, allow notifications, Start NOISE.

## Assets
Converted from the desktop assets to keep the APK small:
GIFs -> animated WebP, WAV -> Ogg Vorbis. The tiny cursor GIFs and PNGs are unchanged.

**Fullscreen scenes are remade in portrait** (360x720, 9:18) straight from the original 960x540
frames, with their real transparency kept (they fade in and out over whatever is on screen, so
nothing is drawn behind them). Each is the original's centre 9:18 strip at full height, same 25 fps
timing: the jumpscare static/VHS screen with the PAUSE / PLAY text re-placed at the bottom-left,
the glitching face of the hijack scare, and the fire alarm shutter coming down with its hazard
stripe, fire and smoke. They are scaled to *cover* the screen and centre-cropped (no borders); in
landscape they are turned a quarter so the composition stays upright. The shutter's two yellow
door handles sit outside the portrait strip, so only the centre seam and panel detail show.
TV clips are 384x512 as before.

## Status
Written and compile-checked against the Android 34 API with stubbed AndroidX, but **not run on a
device or built with Gradle yet**. Expect to tune dp-based distances (death distance, fade
distance, Noise speed) on a real phone. If the Quick Settings tile can't start the service on
your Android version, it opens the app instead.

## Credits / license
Original project, design, code and assets by Ixar (github.com/Ixars/noisedoors). Doors and the
NOISE entity belong to LSPLASH. Unofficial, non-commercial fan work. Ported under the original's
license: copy `LICENSE.MD` (RENC-1.0) from the desktop project into this folder.
