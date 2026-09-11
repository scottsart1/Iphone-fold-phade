# Research: Reverse-engineering the Apple iPhone Duo fold transition

*Research conducted 2026-09-11, two days after the iPhone Duo announcement (2026-09-09).*

This document records what was **observed and verified** from public sources, what was
**inferred**, and what remains **unknown**. Implementation constants in
`animation-engine/` cite the section numbers here. Nothing in the codebase invents a
number without a reason recorded in this file.

---

## 1. The Apple reference animation

### 1.1 What Apple actually shipped

Apple announced the foldable **iPhone Duo** on **9 September 2026** (book-style foldable,
~5.4" cover display, ~7.6" inner display). The fold transition was singled out by
essentially every hands-on as the standout software detail.

Verified descriptions from press coverage:

| Observation | Source |
| --- | --- |
| "A dedicated transition effect that makes the interface appear to **blur and stretch** as it moves between the two screens, so the change in form factor feels continuous rather than abrupt." | Gulf News |
| "Content does not simply jump from the 5.4-inch display to the 7.6-inch display. A dedicated transition animation **blurs, stretches, and flows** the interface across displays." | Gulf News / aggregated hands-on |
| "Unfolding the device triggers a blur animation on the **right side of the cover screen** and the **left side of the internal screen**." | Gulf News |
| "Subtle **gradient and depth-of-field** effects that sell the illusion as the phone unfolds." | Hands-on quote, "a work of art" |
| "It's like you're **seeing through** from the outer screen to the larger screen." | Hands-on quote |
| "Apple really thought through the handoff from the cover screen to the main display. The handoff from one screen to the other is **perfection, a precision of connection**." | Hands-on quote |
| Apple "treats the fold itself as part of the interface, **animating the software as if it is physically responding to the hinge**." | Gulf News |

### 1.2 The five load-bearing conclusions

These are the observations that actually drive the implementation.

**(A) The blur is hinge-directed, not uniform.**
This is the single most specific and most useful published detail. Blur appears on
*the right side of the cover screen* and *the left side of the inner screen*. On a
book-style foldable those are **the two edges that meet at the fold**. The effect is
therefore a **gradient blur anchored to the hinge axis**, strongest at the seam and
falling off toward the outer edges — not a uniform full-screen blur.

> Implemented as `hingeBlurBias` / `hingeFalloff` in `FoldTuning`, evaluated per-pixel
> in `fold_transition.agsl` against a signed distance from the hinge axis in scene space.

**(B) It is a "see-through", not a "fade to black".**
"Seeing through from the outer screen to the larger screen" and "depth-of-field"
describe a **defocused crossfade between two representations of the same scene**, in
which the cover display begins to reveal the *inner* layout beneath a blur. The
project brief proposed a heavy black veil around the handoff. The evidence says Apple
does **not** do that — black is used sparingly, if at all.

> Implemented: `blackHandoffPeak` defaults to **0.12**, not ~1.0. The full-black route
> remains available (`Preset.SafeHandoff`, `blackHandoffPeak = 0.85`) because a black
> mask is the only *guaranteed* way to hide a hardware discontinuity if a particular
> device flashes. Both presets ship; the Apple-like one is the default.
> See `FoldTuningPresets.kt`.

**(C) "Stretch" + "flows across displays" = one continuous scene, two viewports.**
Content is not scaled up from a cover-sized bitmap. It *expands outward*, which is what
you get when a single scene is viewed through a viewport that grows. This motivates the
**portal model** in §4 below, and is the reason the brief's instruction — "do not blindly
scale the cover screenshot until it fills the inner display" — is correct.

**(D) The animation is coupled to the hinge, not timed.**
"Animating the software as if it is physically responding to the hinge." Confirmed
independently by the Galaxy recreation (§2), whose author states the animation plays
"at the speed the phone unfolds."

**(E) The aesthetic is restrained.**
"Subtle", "understated", "precision". No overshoot, no spring bounce, no particle
flourish. Curves are monotone eases. This is why `FoldAnimationEngine` has no spring
physics on the main path.

### 1.3 What could not be verified

- Exact durations/ranges — meaningless anyway, since the animation is hinge-coupled.
- Whether opening and closing are visually asymmetric. **Unknown.** The brief's
  instruction (implement closing as the mathematical reverse first) is followed;
  a `directionalBias` hook exists for later asymmetry but defaults to 0.
- Whether Apple blacks out at all during the physical panel handoff. Not visible in any
  published still. Treated as "minimal black" per (B).
- The Human Interface Guidelines for iPhone Duo were not retrievable in machine-readable
  form at time of writing. Conclusions above are from hands-on press only.

---

## 2. The Galaxy recreation (prior art)

Reddit user **moomanjohnny** published a proof-of-concept on a **Galaxy Z Fold 8** on
~9–10 September 2026, covered by SamMobile, Android Authority, 9to5Google,
Android Headlines and Sammy Fans.

**What they built, per the coverage:**

- An ordinary app that reads **hinge sensor values**.
- Uses the **Presentation API** to drive the second physical display.
- An **AGSL shader** that **interpolates between two screenshots** (cover + inner) based
  on the **current hinge angle**.
- Result: "opening the Fold 8 plays an animation at the speed the phone unfolds."

**The stated caveat**, from the author: *"Samsung doesn't provide the necessary access
for us to build this as a third-party app"* — it cannot replace Samsung's SystemUI
transition and remains a proof of concept, at best a Good Lock–level customisation.

**Conclusion for this project.** The technique stack is validated:
`hinge sensor → normalized progress → AGSL shader → two displays`. This project does not
copy that implementation (no source was published); it independently builds the same
stack, plus the parts the POC explicitly lacks: calibration, filtering, a state machine,
frame-synchronised rendering, a tuning surface, failsafes, and a semantic launcher scene
so the transition is not permanently screenshot-bound.

The caveat is also confirmation of §6: a third-party APK **cannot** replace the system
transition. This project is honest about that boundary rather than pretending otherwise.

---

## 3. Android platform capabilities (what we actually get)

### 3.1 `Sensor.TYPE_HINGE_ANGLE`

- Constant `36`, string type `android.sensor.hinge_angle`, added in **API 30**.
- Unit: **degrees**. `values[0]` is the angle.
- Reporting mode: **on-change**. It fires when the angle changes, *not* at a fixed rate —
  so the event rate is a function of how fast you are moving the hinge, and drops to zero
  when the hinge is still. This is exactly why rendering must be driven by
  `Choreographer` and not by the sensor callback (§5).
- Range is device-defined. **The brief is right not to hard-code 0–180.** Samsung Fold
  hardware has been reported to sit around 178.5–181.5° when "flat", so the open end can
  read *above* 180. The closed end is often not exactly 0 either.
  → Hence the calibration wizard (`fold-sensors/Calibration.kt`) is mandatory, and the
  whole engine runs on normalized progress.
- **Correction from hardware (2026-09-11, SM-F966U):** it is a **wake-up** sensor on the
  Galaxy Z Fold 7, not non-wakeup as originally assumed here.
- **Also from hardware, and far more consequential:** the Fold 7 declares
  `resolution = 90.0` over a `0 … 180` range. Read literally that is three reportable
  values, which would make a continuously-scrubbed animation impossible from this sensor.
  Declared resolution is not trustworthy — vendor HALs often fill it with a placeholder —
  so the app now ships a `SensorProbe` that counts the distinct values each candidate
  sensor actually emits during a fold, and lets the usable one be chosen on evidence. The
  same device exposes three Samsung fold sensors (`folding_angle` 65686,
  `folding_state`/`lid_angle_fusion` 65695, `folding_state_lpm` 65697) declaring `0.01`
  resolution, which are the fallbacks if the standard sensor really is quantised.

### 3.2 Jetpack WindowManager

- `FoldingFeature` gives **discrete** state only: `State.FLAT` / `State.HALF_OPENED`,
  plus `bounds`, `orientation` (`VERTICAL`/`HORIZONTAL`) and `occlusionType`.
  It does **not** expose continuous degrees. Confirms the brief's §20: use
  `FoldingFeature` for *geometry*, the sensor for *angle*.
- `WindowAreaController` (window 1.2.0+) offers:
  - `OPERATION_PRESENT_ON_AREA` → `presentContentOnWindowArea()`, giving a
    `WindowAreaSessionPresenter` whose `setContentView()` renders on the **cover
    display while the device is unfolded** (concurrent/dual-screen mode).
  - `OPERATION_TRANSFER_ACTIVITY_TO_AREA` → `transferActivityToWindowArea()` (rear
    display mode; inner display turns off).
  - Capability statuses: `UNSUPPORTED` / `UNAVAILABLE` / `AVAILABLE` / `ACTIVE`.
- **Important limitations found:**
  - `presentContentOnWindowArea` is only `AVAILABLE` while the device is **unfolded**,
    and the system shows a **non-customisable confirmation dialog**. So it cannot be used
    to silently own the cover display *during* the fold itself.
  - Concurrent display support is device-dependent (documented for Pixel Fold on
    API 34+); Samsung's support must be probed at runtime, not assumed.
  → The app probes at runtime and degrades. See `docs/LIMITATIONS.md`.

### 3.3 AGSL / `RuntimeShader`

- **API 33+**. Skia-backed, GLSL-like.
- `RuntimeShader.setInputShader(name, shader)` binds textures; `.eval(coord)` samples.
- `RenderEffect.createRuntimeShaderEffect(shader, uniformName)` binds the *RenderNode's
  own content* as an input shader — the route to post-processing live composables.
- In Compose: `Modifier.graphicsLayer { renderEffect = ... }`.
- Uniform updates (`setFloatUniform`) do **not** recompile the shader — cheap per frame.
  Creating a `RuntimeShader` **does** compile, so it must be cached (§22 of brief).

### 3.4 Samsung cover ↔ inner display switching

Observed/documented behaviour relevant to us:

- One UI decides when to power the inner panel and move the task. The switch happens at a
  device-defined hinge angle, not one an app can configure or intercept.
- "Continue apps on cover screen" is a *user* setting; with it off, the cover display is
  restricted to notifications/widgets for third parties.
- The activity typically receives a **configuration change / display change**, and may be
  **recreated** — hence brief §19 (state must survive recreation).
- **We cannot observe the exact instant of the panel handoff ahead of time.** What we
  *can* do is **measure it**: record the hinge progress at which our activity's `Display`
  id changes, persist it, and centre the concealment window on it. That is what
  `HandoffLearner` does, and it is the single most valuable device-specific calibration
  in the project.

---

## 4. The visual model we derived

### 4.1 The portal model (core idea)

Rejecting "scale a cover screenshot up to fill the inner display", we model:

> **There is one scene. Each physical display is a viewport onto that scene.**

- Scene space is normalized `[0,1] × [0,1]` and represents the **unfolded** layout.
- The cover display's native framing is a sub-rectangle `coverSceneRect` of that scene
  (a tall, narrow crop on one half — which half is calibratable).
- The inner display's framing at rest is the whole scene.
- During the transition the rendered viewport interpolates
  `coverSceneRect → fullScene`, eased.

Two consequences, both of which are the point of the whole project:

1. Content **expands outward from the hinge**, matching Apple's "stretch / flows across
   displays" rather than a uniform zoom. (§1.2 C)
2. **Both physical displays render the same function of `p`.** At the instant the
   hardware swaps panels, the frame the inner display draws is the frame the cover
   display would have drawn. The discontinuity has nothing to reveal. (§1.2 B, §3.4)

### 4.2 Phase map

Derived from §1.2, using the brief's proposed ranges as the starting point and adjusted
where the research contradicted them. All values live in `FoldTuning` and are adjustable
at runtime without recompiling.

| Phase | `p` | Behaviour |
| --- | --- | --- |
| **A — Closed / deadband** | 0.00–0.08 | Nothing moves. Prevents ±0.2° sensor noise from wobbling the UI. |
| **B — Initial opening** | 0.08–0.30 | Understated: scale 1.000→0.985, hinge-edge blur 0→2dp, brightness 1.00→0.97, wallpaper begins receding. Icons stay legible. |
| **C — Collapse toward handoff** | 0.30–0.55 | Hinge-directed blur ramps hard, viewport starts expanding, vignette in, saturation eases off, depth (perspective) increases. |
| **D — Handoff** | 0.48–0.68 *(re-centred on the **measured** handoff progress)* | Peak defocus + peak cross-dissolve. Black veil peaks at only **0.12** by default (§1.2 B). The concealment window is centred on `HandoffLearner`'s measured value, not a guess. |
| **E — Inner reveal / settle** | 0.68–1.00 | Inner scale 1.05→1.00, alpha →1, blur →0, brightness →1. Monotone ease-out. No spring. |

### 4.3 Closing

Implemented as the **mathematical reverse**: `evaluate(p)` is a pure function of `p`, so
closing through `p = 0.42` renders exactly the state opening through `p = 0.42` rendered.
Direction only influences *prediction/smoothing* and *which side of the handoff window we
bias toward*, never the geometry. `directionalBias` defaults to `0.0`. (Brief §11;
asymmetry unverified per §1.3.)

---

## 5. Timing architecture

- `SensorEvent.timestamp` is `elapsedRealtimeNanos`-based on Samsung hardware;
  `Choreographer` frame times are `System.nanoTime()`-based. These are **different clock
  bases**. The pipeline measures the offset once and converts, so velocity is computed in
  a single consistent time base. (`fold-sensors/TimeBase.kt`)
- Sensor events set a **target**. `Choreographer` drives **rendering**. The One Euro
  filter is evaluated **at frame time**, not at event time, so a still hinge (which emits
  no events) still settles smoothly and a fast hinge does not alias to the event rate.

---

## 6. The honest boundary

A third-party, non-root APK **cannot** replace Samsung's own cover↔inner SystemUI
transition. This is confirmed by (a) the POC author's own statement (§2) and (b) the
absence of any public API to intercept or suppress the system's display-switch animation.

What a normal APK *can* do is own the transition **for its own content** — which is
total when the app is the active launcher, and partial (shader-only, no content capture)
for arbitrary third-party apps. Full details, per requirement §31 of the brief, are in
`docs/LIMITATIONS.md`.

---

## Sources

- https://gulfnews.com/technology/iphone-duos-folding-animation-is-a-feature-no-other-foldable-phone-have-1.500669297
- https://9to5google.com/2026/09/10/iphone-duo-unfold-animation-recreated-for-galaxy-z-fold-8/
- https://www.sammobile.com/news/someone-made-an-iphone-duo-animation-for-the-galaxy-z-fold-8-with-a-huge-caveat
- https://www.androidauthority.com/apple-iphone-duo-animation-samsung-galaxy-z-fold-8-3709841/
- https://www.androidheadlines.com/2026/09/galaxy-z-fold-8-iphone-duo-opening-animation.html
- https://www.macrumors.com/2026/09/09/apple-announces-foldable-iphone-duo/
- https://gizmodo.com/iphone-duo-hands-on-2000808932
- https://www.techradar.com/phones/iphone/iphone-duo-hands-on
- https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes
- https://developer.android.com/reference/android/hardware/Sensor
- https://developer.samsung.com/sdp/blog/en/2024/09/26/unfold-the-potential-of-galaxy-fold-devices-with-windowmanagers-rear-display-mode
- https://medium.com/androiddevelopers/agsl-made-in-the-shade-r-7d06d14fe02a
