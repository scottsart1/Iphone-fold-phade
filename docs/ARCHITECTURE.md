# Architecture

## The pipeline

Brief §28's reference architecture, as actually built:

```
  HingeAngleSensorSource ──┐
                           ├─→  FoldProgressSource        (one interface, two impls)
  VirtualHingeSource    ──┘
                                      │  raw HingeSample (angle°, frame-clock nanos)
                                      ▼
                            ┌─────────────────────┐
                            │    FoldPipeline     │  ← Choreographer drives this
                            ├─────────────────────┤
                            │ TimeBase            │  reconcile the two nano clocks
                            │ HingeCalibration    │  degrees → normalised progress
                            │ OneEuroFilter       │  jitter out, latency kept
                            │ VelocityEstimator   │  least-squares over ~80 ms
                            │ FoldStateMachine    │  hysteresis + dwell
                            │ HandoffLearner      │  measure the real panel swap
                            └─────────────────────┘
                                      │  FoldProgress
                                      ▼
                            FoldAnimationEngine           pure: progress → state
                                      │  FoldVisualState
                          ┌───────────┴───────────┐
                          ▼                       ▼
                 FoldShaderProgram        LauncherSceneRenderer
                 (AGSL, textures)         (semantic home scene)
                          │                       │
                          └───────────┬───────────┘
                                      ▼
                          Cover display  /  Inner display
```

---

## The five decisions that matter

Everything else is consequence.

### 1. The engine is a pure function of progress

`FoldAnimationEngine.evaluate(progress, velocity, direction, handoffCenter)` has no clock,
no animator, no internal state that advances on its own.

This is not a stylistic preference — it is the mechanism by which the acceptance criteria
hold:

| Criterion | Why it holds |
| --- | --- |
| Pausing the hinge pauses the effect | Nothing in the engine can change without `progress` changing |
| Reversing reverses immediately | `evaluate(0.42)` returns the same value going down as going up |
| Closing is the reverse of opening | One code path, not two kept in sync |

Velocity and direction feed only *concealment* (blur depth, veil), never *geometry*.
A unit test asserts that `viewport` and `coverScale` are identical for a still hinge and
a fast one.

### 2. Sensors set the target; Choreographer drives rendering

`TYPE_HINGE_ANGLE` is an **on-change** sensor. Its event rate is a function of how fast
you are moving the hinge, and it goes completely silent when you hold still.

Rendering from the sensor callback would therefore tie the animation's frame rate to hinge
speed: smooth when moving fast, stuttery when creeping, frozen when held. Exactly
backwards.

So the callback does one thing — stash the sample in an `AtomicReference` and return. A
`Choreographer` callback, one per display frame, picks it up and runs the whole pipeline
**at frame time**. A held hinge still settles smoothly despite emitting nothing; a burst
of events between two vsyncs collapses into one coherent visual step.

### 3. The portal model

> One scene. Each display is a viewport onto it.

The alternative — scale a cover-sized bitmap up until it fills the inner display — looks
cheap because it scales *everything*, including elements whose perceived size should not
change.

Instead, scene space is normalised `[0,1]²` describing the unfolded layout.
`SceneMapping.coverSceneRect` is the crop the cover display natively frames; the viewport
interpolates from it to the whole scene as progress advances.

Two things fall out, and they are the point of the project:

1. Content **expands outward from the hinge** rather than zooming uniformly.
2. **Both panels evaluate the same function of progress**, so the frame at the hardware
   swap matches. The discontinuity has nothing to reveal.

### 4. The handoff point is measured, not guessed

One UI decides when to swap panels. No app can configure or query it, and it varies by
device, firmware and user settings.

`HandoffLearner` records the progress at which our window changes display, keeps a running
**median** (a mean would be dragged by the late first-boot observation), and persists it.
The concealment window centres on that.

This is the single most valuable device-specific calibration in the project, and it costs
the user nothing — it happens while they use the phone normally.

### 5. Blur is hinge-directed, not uniform

From `RESEARCH.md` §1.2(A), the most specific published detail about Apple's version: the
blur appears on *the side of each screen that meets the fold*.

`fold_transition.agsl` computes a signed distance from the hinge line per fragment and
interpolates between a hinge-edge radius and an outer-edge radius. The dissolve front also
advances outward from the fold, so content resolves from the seam outward rather than
crossfading uniformly.

---

## Timing: the two-clock problem

`SensorEvent.timestamp` on Samsung hardware is on the **`elapsedRealtimeNanos`** base — it
keeps counting while the device is suspended. `Choreographer` frame times are on the
**`System.nanoTime`** base — it does not.

Subtracting one from the other yields a velocity wrong by however long the phone has been
asleep. On a device that spends most of its life suspended, that is catastrophic rather
than subtle.

`TimeBase` measures the offset, applies it, re-measures every 5 s to absorb drift, and
sanity-checks the result — an implausible conversion falls back to "now" rather than
emitting a wildly wrong delta.

This is why the brief's instruction to use sensor timestamps rather than wall-clock timing
is right *but incomplete*: it is right only once both are in the same base.

---

## Filtering: why One Euro

The real requirement (brief §5) is a genuine tension: remove jitter **without** adding
perceptible lag. No fixed-cutoff filter can do both — heavy smoothing kills the jitter and
also the responsiveness that makes the effect feel like direct manipulation.

One Euro makes the cutoff a function of the signal's own speed. Hinge nearly still → low
cutoff, jitter crushed. Hinge moving fast → high cutoff, filter gets out of the way.

Two tests pin both halves down: filtered output must stay within 0.15° of a still-but-noisy
signal, and must lag a 180°-in-300 ms snap by less than 18°.

`VelocityEstimator` least-squares-fits a line over ~80 ms rather than differencing two
samples, because the sensor is quantised: a two-point difference reports either 0 or a
large spike depending on whether a quantisation step landed in the interval, and feeding
*that* to the direction logic makes it thrash.

---

## Direction hysteresis

Brief §6: do not bounce between OPENING and CLOSING on ±0.2° of noise.

Two independent mechanisms:

1. **Asymmetric velocity band.** 6°/s to *enter* a direction, 2°/s to *stay* in it. Since
   enter > exit, noise around zero cannot flip the state while genuine slow motion is
   still tracked.
2. **Dwell time.** A candidate direction must persist 24 ms (~3 frames at 120 Hz) before
   being adopted, which rejects single-sample spikes outright.

Relaxing *into* HOLD is allowed immediately — stopping must look instant, and a false stop
is invisible because the visual state simply holds.

A test drives 500 noise samples through the machine and asserts **zero** direction flips.

---

## Performance

Everything the brief's §22 forbids, and where it is structurally prevented:

| Forbidden | Prevented by |
| --- | --- |
| File reads during animation | AGSL source is a Kotlin constant — nothing to read |
| Bitmap decoding per frame | `SceneTextureCache` decodes once, off-thread, at import |
| Large allocation per frame | Ring buffers with primitive arrays; `apply()` uses varargs |
| Repeated screenshot capture | Never captured; textures are imported or rendered once |
| Shader recompilation | `remember { FoldShaderProgram() }`; uniforms are cheap |
| Unnecessary recomposition | `FoldTransitionSurface` takes `State<FoldVisualState>` and reads it **inside** `drawBehind`, so only the draw phase invalidates |

That last one is a signature-level decision: taking a plain `FoldVisualState` parameter
would invalidate composition on every sensor frame. The type makes the mistake impossible.

**Idle behaviour (brief §23).** The Choreographer loop is *not* continuous. It runs while
the fold moves, plus a 12-frame settle tail, then stops. Only the on-change sensor stays
registered, which costs essentially nothing when nothing is touching the hinge.
`FoldPipeline.isAnimating` exposes this so the renderer can release GPU resources.

---

## The shader

`FoldShaderSource.SOURCE`, compiled once per quality level.

Per fragment:

1. Output pixel → scene space through the current viewport.
2. Signed distance from the hinge → per-pixel blur radius.
3. Sample both scene representations with a variable-radius **golden-angle spiral** blur —
   taps distribute far more evenly than a square grid for the same count, and avoid the
   axis-aligned banding a box kernel produces on hard edges.
4. Cross-dissolve with a hinge-directed gradient.
5. Depth cues: hinge-centred vignette, fold shadow, specular edge, dim, desaturate.
6. Black veil (small, by default).

Two portability decisions, both deliberate:

- The blur is **written out twice**, once per texture, rather than factored into a
  function taking a `shader` parameter. Passing child shaders as function arguments is not
  reliably supported across Skia versions; calling `.eval()` on the uniform directly
  always is.
- Tap count is **substituted into the source** at compile time rather than passed as a
  uniform. AGSL does not permit a uniform loop bound, and branching inside the loop would
  not save fill rate anyway.

Tap count is the dominant cost: `HIGH` 13, `MEDIUM` 9, `LOW` 5, switchable live from the
tuning panel next to the P99 readout.

---

## State across a fold

Folding changes `screenSize`, `smallestScreenSize`, `screenLayout`, `orientation` and
`density` simultaneously. By default that destroys and recreates the activity — during the
exact moment it is supposed to be animating.

Two defences:

1. `android:configChanges` declares all fold-related axes, so an ordinary fold does not
   recreate the activity.
2. **Nothing important lives in the activity anyway.** `FoldController` is
   application-scoped and owns the pipeline, engine, learner and textures. A `ViewModel`
   would survive rotation, but this state must survive something stronger — the very event
   it is animating — and must keep running while no activity exists at all, for the
   overlay path.

Per brief §19, per-frame values are memory-only. DataStore holds calibration and tuning,
which are genuine preferences.
