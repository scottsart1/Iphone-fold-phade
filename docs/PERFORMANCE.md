# Performance

## Budget

At 120 Hz there are **8.33 ms** per frame. The effect is full-screen, so it is fill-rate
bound, and the tap count is the dominant cost.

| Target | Value |
| --- | --- |
| Average frame time | < 8.33 ms |
| P95 | < 8.33 ms |
| P99 | < 12 ms |
| Dropped frames | < 1% |
| Idle CPU while open | ~0 — the frame loop is stopped |

Brief §22's ordering is followed throughout: **a simple perfect animation beats a
beautiful animation that stutters.**

---

## Measured here vs. on device

**Measured in CI (no foldable attached):**

| | |
| --- | --- |
| Release APK size | **2.4 MB** (minified + resource-shrunk) |
| Debug APK size | 61 MB (Compose tooling, not shipped) |
| Unit tests | 56, all passing |
| Compiler warnings | 0 |
| `INTERNET` permission | none |

**Not measured here — requires your Z Fold 7:**

Frame timings. The `HIGH` (13-tap) default is a considered estimate based on the shader's
sampling cost against a Fold-7-class GPU, **not a measurement**. Treat it as a starting
point, exactly like every other tunable in this project.

The Tuning tab shows live average / P95 / P99 / dropped / effective-Hz while you fold, and
the quality selector sits directly beneath it. If P99 misses your budget, drop one step and
re-measure — the loop takes seconds.

---

## Where the time goes

Per fragment, per frame:

| Cost | Notes |
| --- | --- |
| **Texture sampling** | `taps × textures`. Dominant. 13 taps × 2 textures = 26 samples worst case |
| Scene mapping | A few MADs. Negligible |
| Grade (brightness/saturation) | One dot product + two lerps per texture |
| Depth cues | Each behind a uniform branch; all skip when their intensity is ~0 |

Two early-outs matter:

- `radiusPx < 0.35` takes a **single** `eval()` instead of the kernel. Outside the
  concealment window most of the screen hits this.
- The inner texture is only sampled when `hasInner > 0.5`. Before an inner screenshot is
  imported, the cost is halved.

### Quality levels

| Level | Taps | Relative sampling cost | Use when |
| --- | --- | --- | --- |
| `HIGH` | 13 | 1.00× | Default. 120 Hz target |
| `MEDIUM` | 9 | 0.69× | If P99 is marginal |
| `LOW` | 5 | 0.38× | 60 Hz mode, battery saver, or a measured fill-rate limit |

---

## What is structurally prevented

Brief §22's prohibitions, and the mechanism that enforces each — these are design
properties, not conventions someone has to remember:

| Forbidden | Enforced by |
| --- | --- |
| File reads during animation | AGSL source is a Kotlin constant. There is nothing to read |
| Network | No `INTERNET` permission. Cannot happen |
| Bitmap decoding | `SceneTextureCache` decodes once, off-thread, at import |
| Large allocation per frame | Ring buffers of primitive arrays; uniform setters take varargs of primitives, which do not box |
| Repeated screenshot capture | Never captured at all |
| Shader recompilation | `remember { FoldShaderProgram() }`; `setFloatUniform` does not recompile |
| Texture re-upload | `BitmapShader`s cached by bitmap identity; re-setting the same bitmap is a no-op |
| Unnecessary recomposition | `FoldTransitionSurface` takes `State<FoldVisualState>` and reads it **inside** `drawBehind` |

That last row is worth restating: the *signature* prevents the mistake. A plain
`FoldVisualState` parameter would invalidate composition on every sensor frame; taking a
`State` and reading it in the draw lambda confines invalidation to the draw phase.

Two more, from the bug-fixing pass:

- `FoldController.onSample` persists the learned handoff only when the observation
  **count changes**. Ungated, that was a DataStore commit per frame — file I/O on the
  animation path.
- The sensor callback does nothing but store into an `AtomicReference` and return. No
  filtering, no state machine, no allocation.

---

## Idle and power (brief §23)

The Choreographer loop is **not** continuous. It runs while the fold is moving, plus a
12-frame settle tail, then stops entirely. `FoldPipeline.isAnimating` exposes the state so
the renderer can release GPU resources.

What stays registered while idle is only the hinge sensor — and since it is an
**on-change** sensor, a hinge nobody is touching generates no events and therefore costs
essentially nothing.

The two states the brief asks for are explicit:

- **IDLE** — sensor registered, frame loop stopped, no GPU work.
- **ANIMATING** — frame loop running at display rate.

To verify on device: leave the phone open and idle for an hour with the app foregrounded
and check Battery usage. If it is non-trivial, something is holding the loop open — the
frame counter on the diagnostics screen will show it climbing while nothing moves.

---

## Instrumentation

`FrameMetrics` records inter-frame intervals into a fixed 240-entry ring buffer.
Allocation-free on `record()` — an instrument that perturbs what it measures is worse than
no instrument.

A frame counts as **dropped** when its interval exceeds 1.5× the expected frame time,
which catches a genuinely missed vsync without flagging normal jitter around it. The
expected interval comes from the display's reported refresh rate, so the metric stays
correct when the panel drops to 60 Hz.

Reported: average, P95, P99, dropped count, total, effective Hz, expected Hz.

---

## If it stutters

In order:

1. **Drop shader quality** to `MEDIUM`, then `LOW`. Biggest single lever.
2. **Narrow the concealment window** (`Handoff half-width`) — fewer pixels take the
   expensive blur path.
3. **Reduce max blur radii**. Radius does not change tap count, but large radii thrash the
   texture cache.
4. **Turn off depth cues you do not need.** Each of vignette, hinge shadow and edge
   illumination is behind a uniform branch and costs nothing at zero.
5. **Check the trace.** Export the CSV and look at the sensor event rate. If events are
   arriving faster than frames, the pipeline is already coalescing them and the problem is
   downstream in the shader, not in sensing.
