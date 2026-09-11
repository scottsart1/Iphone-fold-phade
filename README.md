# FoldPhase

An Apple-iPhone-Duo-style fold/unfold transition for the **Samsung Galaxy Z Fold 7**,
physically coupled to the hinge.

Not "a fold is detected, play a 400 ms animation." The hinge **is** the timeline:

- Open slowly → the animation progresses slowly.
- Stop halfway → the animation stops at exactly that visual state, indefinitely.
- Reverse → the animation reverses immediately and smoothly.
- Snap it open → the animation moves correspondingly fast.

Your hand is the scrubber.

---

## Status, honestly

| | |
| --- | --- |
| `./gradlew assembleDebug` | ✅ passes, **0 warnings** |
| `./gradlew assembleRelease` | ✅ passes, minified + signed (2.4 MB) |
| `./gradlew test` | ✅ 50 tests pass |
| Verified on a physical Z Fold 7 | ❌ **not yet** — no device in the build environment |

The code was written and built in a Linux container with no foldable attached. Everything
that can be verified without hardware has been. What that leaves open — shader driver
compatibility, real frame timings, the device's actual hinge characteristics — is listed
precisely in [`docs/LIMITATIONS.md` §9](docs/LIMITATIONS.md). Expect a calibration pass
and some curve tuning on first run; that is what the tuning screen is for.

---

## Start here

1. **[`docs/RESEARCH.md`](docs/RESEARCH.md)** — what Apple actually shipped, what the
   Galaxy proof-of-concept did, and the visual model derived from both. Every constant in
   the code cites a section here.
2. **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — how the pieces fit and why.
3. **[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md)** — what a non-root APK cannot do, in
   the required desired/limitation/attempted/workaround/root form.
4. **[`docs/CALIBRATION.md`](docs/CALIBRATION.md)** — run this first on your device.
5. **[`docs/TESTING.md`](docs/TESTING.md)** — the on-device checklist.

---

## The two ideas that make it work

### 1. The portal model

The hard problem is the moment One UI powers off one panel and powers on the other. You
cannot intercept it, configure it, or find out when it will happen.

So this does not try to *hide* the discontinuity. It removes it:

> **There is one scene. Each physical display is a viewport onto that scene.**

Scene space is normalised `[0,1]²` and describes the **unfolded** layout. The cover
display's native framing is a sub-rectangle of it. During the fold, the rendered viewport
interpolates from that crop to the whole scene.

Because **both panels evaluate the same function of hinge progress**, the frame the inner
display draws at the swap is the frame the cover display would have drawn. There is
nothing to conceal.

It also produces the right *motion*: content expands outward from the hinge rather than
zooming uniformly — which is what the reference animation's "stretch / flows across
displays" actually looks like.

### 2. Measuring the handoff instead of guessing it

The panel swap happens at a hinge angle that varies by device, by One UI version, and
with the user's "Continue apps on cover screen" setting. A concealment window centred on a
guess will be wrong, and being wrong is what produces a visible flash.

So `HandoffLearner` **measures** it: every time the app's window changes display, it
records the progress at that instant and keeps a running median. After two or three folds
the concealment window is centred on the truth for *your* phone, and it is persisted.

---

## What the research changed

The project brief proposed going nearly black around the handoff and treating the black
frame as a transition mask. The research disagreed with that, and the research won.

Hands-on coverage of the iPhone Duo describes *"seeing through from the outer screen to
the larger screen"* with *"subtle gradient and depth-of-field effects"*, and blur
appearing specifically on **the right side of the cover screen and the left side of the
inner screen** — the two edges that meet at the fold.

That is a hinge-directed, defocused **see-through crossfade**, not a blackout. So:

- `blackHandoffPeak` defaults to **0.12**, not ~1.0.
- Blur is a **per-pixel gradient anchored to the hinge**, strongest at the seam.
- A unit test fails if anyone raises the default preset's black veil above 0.25, so this
  decision cannot be quietly undone by someone chasing a flash.

The blackout route is still one tap away as the **Safe handoff** preset — it is the only
*guaranteed* way to mask a hardware discontinuity, and if your device flashes you should
use it. It just is not the default, because it is not what the reference does.

---

## Modules

```
core/              Scene coordinates, curve library (pure Kotlin, no Android UI)
fold-sensors/      Hinge sensor · calibration · One Euro filter · velocity
                   regression · direction hysteresis · handoff learner · pipeline
animation-engine/  progress → FoldVisualState. Pure. No clock. No Android.
renderer/          AGSL shader, texture cache, frame-timing instrumentation
launcher/          Semantic HomeScene + interpolating cover/inner renderer
overlay/           Transient system overlay with layered failsafes
diagnostics/       Sensor inventory, trace ring buffer, CSV/report export
app/               Diagnostics · calibration wizard · tuning panel · simulator
```

The dependency graph is acyclic and `animation-engine` depends on nothing but `core`,
which is why the engine is unit-testable without a device.

---

## Install

Prebuilt APKs are produced by the build; there is nothing to download and no account.

```bash
git clone <this repo> && cd Iphone-fold-phade

# Point at your SDK (or let Android Studio do it)
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a release build you need a signing key. Generate a throwaway one:

```bash
keytool -genkeypair -v -keystore app/local-release.jks -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 -alias foldphase \
  -storepass foldphase -keypass foldphase \
  -dname "CN=FoldPhase Local, OU=Personal, O=FoldPhase, L=NA, S=NA, C=NA"

./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The debug build uses application id `dev.foldphase.app.debug`, so debug and release can
be installed side by side.

**Requirements:** Android 13+ (API 33 — `RuntimeShader` needs it), targets Android 16
(API 36). Works on a non-foldable in simulator-only mode.

---

## First run

1. **Sensors tab.** Confirm a hinge sensor was found and that moving the hinge moves the
   number. If it says "No hinge sensor found", everything still works from the virtual
   hinge, but nothing will be physically coupled.
2. **Calibrate tab.** Run the three-step wizard. Do this before judging anything —
   `0° = closed, 180° = open` is not true on this hardware and the app refuses to assume
   it. See [`docs/CALIBRATION.md`](docs/CALIBRATION.md).
3. **Transition tab.** Import a cover screenshot and an unfolded screenshot, then drag the
   virtual hinge slider. Tune here before touching the physical hinge.
4. **Fold the phone.** Now with the real hinge. The first couple of folds also teach
   `HandoffLearner` where your device swaps panels.
5. **Tuning tab.** Adjust against the reference. Watch the P99 frame time while you do.

---

## Permissions

| Permission | Why | Required? |
| --- | --- | --- |
| *(none by default)* | The core app needs nothing | — |
| `SYSTEM_ALERT_WINDOW` | Experimental overlay mode only | Opt-in |
| `FOREGROUND_SERVICE(_SPECIAL_USE)` | Android requires it to host that overlay | With overlay |
| `POST_NOTIFICATIONS` | The persistent "overlay is armed" notification | With overlay |

**No `INTERNET` permission.** No analytics, no ads, no accounts, no telemetry. Calibration
and tuning stay on the device. There is nothing to send anywhere and no way to send it.

---

## The overlay will not get stuck

A full-screen overlay that fails to be removed is a reboot-level failure on a personal
phone. Any one of these removes it, independently:

- 600 ms render heartbeat timeout
- 6 s absolute deadline that nothing can extend
- Screen off · configuration change · shutdown broadcast
- Overlay permission revoked (polled every 2 s)
- Service destruction
- Process death — `WindowManager` removes our windows for us

And the window is `FLAG_NOT_TOUCHABLE`, so even a stuck one would pass every touch
through to whatever is underneath. The service is `START_NOT_STICKY` on purpose: if the
system kills it, it must not silently come back.

---

## License

Personal project. Use it however you like.
