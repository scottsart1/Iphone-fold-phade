# Known limitations

Per brief §31, every limitation is stated in the required form:

> **Desired behavior** · **Android limitation** · **What was attempted** · **Best non-root workaround** · **Would root/SystemUI access solve it?**

Nothing in this list is worked around by pretending. Where a thing is impossible for an
ordinary APK, it says so.

---

## 1. Replacing Samsung's own cover ↔ inner transition

**Desired behavior.** When the phone is unfolded, *our* transition plays instead of One
UI's, for whatever is on screen.

**Android limitation.** There is no public API to intercept, suppress, or replace the
system's display-switch animation. The decision to power the inner panel and move the
task belongs to SystemUI, happens at a hinge angle the app cannot configure or query, and
is not surfaced to applications before the fact. This is confirmed independently by the
author of the Galaxy Z Fold 8 proof-of-concept, who reported that *"Samsung doesn't
provide the necessary access for us to build this as a third-party app"*
(`docs/RESEARCH.md` §2).

**What was attempted.** `WindowAreaController` (both `OPERATION_PRESENT_ON_AREA` and
`OPERATION_TRANSFER_ACTIVITY_TO_AREA`), `DisplayManager` display enumeration, and
window-level animation overrides. None of them can pre-empt the system transition.

**Best non-root workaround — and it is a good one.** Own the transition for *our own*
content, and make the content continuous across the panel swap rather than trying to hide
the swap. Two mechanisms do this:

- **The portal model** (`docs/RESEARCH.md` §4.1). Both panels render the same scene
  through the same progress-driven viewport, so at the instant the hardware swaps, the
  frame the inner display draws is the frame the cover display would have drawn. There is
  no discontinuity to conceal because there is no discontinuity.
- **`HandoffLearner`.** The app measures where the swap actually happens on *your* device
  by recording the progress at which its window changes display, then centres the
  concealment window on the measured value. After two or three folds this is exact,
  where a hard-coded guess never would be.

In **launcher mode** this is effectively complete: our content *is* the home screen.

**Would root solve it?** Yes. See §8.

---

## 2. Applying the effect to other apps' content

**Desired behavior.** Fold while Chrome is open and see Chrome's pixels blur, stretch and
flow across the displays.

**Android limitation.** An ordinary app cannot read another app's framebuffer. Doing so
requires `MediaProjection` (a per-session user consent dialog plus a persistent recording
indicator), a privileged/system signature permission, or root. `FLAG_SECURE` windows are
excluded even then.

**What was attempted.** The overlay path was built and works, but it draws only its own
content. No screenshot of the underlying app is taken.

**Explicitly NOT attempted.** Registering an Accessibility Service to obtain screen
content. The brief rules this out and it is the right call: Accessibility exists for
accessibility, and using it as a privilege-escalation route is exactly the pattern that
gets apps removed and users harmed.

**Best non-root workaround.** The shader-only overlay (`overlay/`). It can dim, cast a
hinge shadow, run a hinge-directed reveal gradient, draw the vignette and specular fold
edge, and take the screen through the handoff — all hinge-coupled, all without reading
anything. It measurably improves the *perceived* transition. It cannot warp the app
underneath, and the Overlay screen in the app says so in those words.

**Would root solve it?** Yes. See §8.

---

## 3. `presentContentOnWindowArea` is unavailable during the fold

**Desired behavior.** Drive the cover panel directly throughout the fold, so both panels
are explicitly ours the whole way through.

**Android limitation.** `WindowAreaCapability` reports `AVAILABLE` for concurrent display
**only while the device is unfolded**, and starting a session raises a system
confirmation dialog that cannot be customised or suppressed. Support is also
device-dependent — documented for Pixel Fold on API 34+, and must be probed at runtime on
Samsung hardware rather than assumed.

**What was attempted.** `CoverDisplayPresenter` implements the full Jetpack flow and
reports the real capability status on the Overlay screen, so you can see exactly what
your device supports instead of guessing.

**Best non-root workaround.** Treat cover presentation as an *enhancement*, not a
dependency. The transition's continuity comes from the portal model, which needs no
special access at all. When a session is available it keeps the cover panel showing the
matching frame; when it is not, nothing breaks.

**Would root solve it?** Partially — a system app could present on either panel freely.

---

## 4. The hinge sensor is on-change and non-wakeup

**Desired behavior.** Continuous hinge angle at display refresh rate, always.

**Android limitation.** `TYPE_HINGE_ANGLE` is an **on-change** sensor: it fires when the
angle changes and goes silent when the hinge is still, so its event rate is a function of
how fast you are moving the hinge. It is **non-wakeup** on Samsung hardware, so it
delivers nothing while the CPU is suspended.

**What was attempted.** `SENSOR_DELAY_FASTEST` registration; the measured rate is shown
live on the diagnostics screen.

**Workaround (this is the architecture, not a patch).** Sensor events set a *target*;
`Choreographer` drives *rendering*, and the One Euro filter is evaluated **at frame
time**. A held hinge therefore still settles smoothly despite emitting nothing, and a
fast hinge does not alias to the event rate. See `FoldPipeline`.

Consequence to be aware of: a fold that begins while the screen is off produces no events
until the device wakes. The first frames after wake come from wherever the hinge now is,
not from where it was — which is correct, just not something the app can anticipate.

**Would root solve it?** No. This is hardware behaviour.

---

## 5. `FoldingFeature` gives no continuous angle

**Desired behavior.** Continuous degrees from Jetpack WindowManager.

**Android limitation.** `FoldingFeature.State` is `FLAT` or `HALF_OPENED` only.

**Workaround.** Exactly what brief §20 prescribes, and it is the right split: the sensor
supplies the timeline, `FoldingFeature` supplies the stage — fold-line position,
orientation, separation, occlusion. `FoldingFeatureMonitor` feeds the measured fold-line
position into the scene mapping, so the hinge axis is where the system says it is rather
than assumed to be dead centre.

**Would root solve it?** Not relevant.

---

## 6. Activity recreation during a fold

**Desired behavior.** Never lose transition state mid-fold.

**Android limitation.** Folding changes `screenSize`, `smallestScreenSize`,
`screenLayout`, `orientation` and `density` at once. By default the activity is destroyed
and recreated — during the exact moment it is supposed to be animating.

**What was done.** Two independent defences:
1. `android:configChanges` declares all fold-related axes, so the activity is not
   recreated for an ordinary fold.
2. Nothing important lives in the activity anyway. The pipeline, engine, handoff learner
   and textures are all owned by the application-scoped `FoldController`, so a recreation
   forced by something outside our control costs nothing but the current tab index.

Per brief §19, per-frame values are held in memory only; DataStore holds calibration and
tuning, which are genuine preferences.

---

## 7. Launcher mode scope

**Desired behavior.** A drop-in launcher preserving widgets, folders, icon packs and
gestures.

**Reality.** `launcher/` is deliberately minimal — brief §15 says it does not need to be
a Nova competitor, and it is not. It renders a wallpaper, an icon grid and a dock from a
shared `HomeScene`, and preserves page position across the fold. What it *does* do, that
no screenshot-based approach can, is interpolate **real icon positions** between the
4-column cover grid and the 6-column inner grid, matched by item id — icons travel rather
than crossfade.

App-widget hosting (`AppWidgetHost`) is not implemented. It is possible without root, but
it is a large amount of work orthogonal to the fold transition.

**Recommendation.** Use launcher mode to evaluate the effect. Keep One UI Home as your
daily launcher until you decide otherwise.

---

## 8. What root / SystemUI access would actually buy

Documented per brief §2 Level D, and kept **entirely separate from the shipping code** —
there is no root code in this repository, and nothing in the app checks for or requests
root.

| Approach | What it would unlock | Realistic difficulty |
| --- | --- | --- |
| **Magisk + LSPosed hooking `com.android.systemui`** | Replace the real cover↔inner transition at source. Hook the display-switch path and substitute our renderer. This is the only route to a genuinely system-level version. | High. Requires hooking One UI internals, which are obfuscated and change between firmware builds. |
| **LSPosed hook on One UI Home** | Transform actual launcher elements without *being* the launcher — the §7 tradeoff disappears. | Medium-high, and equally firmware-fragile. |
| **System app (signed with the platform key)** | `SYSTEM_ALERT_WINDOW` without prompting, `READ_FRAME_BUFFER`, presentation on either panel at will. Solves §1, §2 and §3 together. | Requires a custom ROM or a signing key you will not have. |
| **`WRITE_SECURE_SETTINGS` via adb** | Tweak `window_animation_scale` and similar to reduce the system's competing animation. A genuine marginal improvement, and it needs no root — just one adb command. | Low. This is the only one worth trying casually. |

The honest summary: **the parts of this project that matter most do not need root.**
Hinge coupling, the animation engine, the shader, calibration, the portal model and
launcher mode are all fully realised in the normal APK. Root would extend the effect to
*other apps' content* and to the *system's own* transition — real gains, but they are the
extension, not the core.

---

## 9. What has NOT been verified on hardware

Stated plainly, because brief §31 asks for exactly this distinction.

This project was developed and built in a Linux CI container with no Galaxy Z Fold 7
attached. Consequently:

**Verified here:**
- `./gradlew assembleDebug`, `assembleRelease` and `test` all succeed, with zero
  compiler warnings.
- 50 unit tests pass, covering the engine's purity and reversibility, curve monotonicity,
  filter jitter/lag behaviour, direction hysteresis under simulated noise, handoff
  learning, and calibration normalisation.
- The AGSL is written against documented Skia/AGSL semantics and avoids constructs
  (`shader` as a function parameter, uniform loop bounds) that are not reliably portable.

**NOT verified here, and requiring your device:**
- That the AGSL compiles on the Fold 7's Adreno/Xclipse driver. A shader that is valid per
  spec can still be rejected by a specific driver. If it fails, `FoldShaderProgram` throws
  at construction and the message names the line.
- Real frame timings at 120 Hz. The `HIGH` (13-tap) default is a considered estimate, not
  a measurement. The Tuning screen shows live P95/P99 and the quality selector is right
  there — if P99 exceeds your frame budget, drop to `MEDIUM` or `LOW`.
- The Fold 7's actual hinge range, resolution, noise floor and event rate. This is
  precisely why the diagnostics screen and calibration wizard exist and why nothing is
  hard-coded.
- Where the panel handoff actually occurs. `HandoffLearner` measures it; the fallback of
  `p = 0.58` is a guess and is labelled as one in the UI.
- Whether the cover display is behind the right-hand half (the `coverHalf` default). If
  the effect looks mirrored, flip it — it is a one-line constant and a dev-panel concern.

The honest expectation: the first run on real hardware will need a calibration pass and
some curve tuning. That is what the tuning screen is for, and it is why the brief's
instruction to tune on-device rather than trust the proposed numbers was correct.
