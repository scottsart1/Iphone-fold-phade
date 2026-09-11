# Device calibration

**Run this first.** Nothing else in the app is trustworthy until you have.

## Why it is not optional

The obvious model is `0° = closed, 180° = open`. It is wrong on this hardware:

- Samsung Fold hinges have been reported reading **178.5–181.5°** when flat — the open end
  can sit *above* 180.
- The closed end is frequently not 0 either; most book foldables do not physically reach
  a true 0°, and forcing one would be the wrong reference anyway.
- Resolution, noise floor and event rate are all device- and unit-specific.

If the app assumed 0–180 and your device reported 4–181, every visual phase boundary would
be shifted by a couple of percent — enough that the concealment window would not sit on the
panel swap, which is the one thing it has to do.

So the app **refuses to assume**, and everything downstream operates on normalised
progress rather than degrees.

---

## Before you start

Open the **Sensors** tab and check:

- A hinge sensor was found. You want `TYPE_HINGE_ANGLE` (36) with string type
  `android.sensor.hinge_angle`.
- Moving the hinge moves the number.
- If the app warns *"matched by NAME HEURISTIC"*, it found something hinge-shaped but not
  via a standard identifier — treat its units with suspicion and calibrate carefully.

If no sensor was found, calibration is moot; the app will run from the virtual hinge only.

---

## The wizard

**Calibrate** tab → **Start**.

### Step 1 — Closed

Close the phone as far as it naturally goes. Do not force it. Capture.

### Step 2 — Open

Open it completely flat on a table. Capture.

### Step 3 — Slow sweep

Slowly open and close once, a few seconds each way. **Pause for a second or two at two or
three points** — the pauses are what let the app measure the noise floor, which it does by
finding the quietest 16-sample window in the sweep.

Watch the live statistics as you go:

| Reading | What it tells you |
| --- | --- |
| **Samples** | Needs ≥ 120 to finish |
| **Distinct values** | If this is tiny, the sensor is coarsely quantised or stuck |
| **Range seen** | Should roughly match your two captures |
| **Resolution** | Smallest non-zero step. Often 0.1–1.0° |
| **Median interval** | Typical gap between events while moving |
| **Largest jump** | A big value means a dead zone — see below |
| **Held noise (σ)** | The noise floor. Drives the deadband |

### Review and save

Extremes are widened to include anything seen during the sweep, so a slightly conservative
capture still yields the full range.

If **span is implausibly small**, the app says so and you should re-run — it usually means
the hinge did not actually move between the two captures.

---

## Reading your results

Back on the **Sensors** tab, compare:

- **Min/Max observed** — what the sensor has actually reported this session.
- **Calibrated closed/open** — what is stored.

If they drift apart over time, re-calibrate.

### Noise (σ)

| Value | Meaning |
| --- | --- |
| < 0.05° | Excellent. You can lower the hysteresis thresholds |
| 0.05–0.3° | Normal |
| > 0.5° | Noisy. Raise `One Euro min cutoff` and the enter/exit thresholds |

Set the direction thresholds from *this number*, not by feel. The Tuning tab's
`Hysteresis: enter (°/s)` should sit comfortably above your noise-driven velocity and below
your slowest deliberate movement.

### Median update interval

| Value | Meaning |
| --- | --- |
| < 10 ms | Faster than a 120 Hz frame — ideal |
| 10–20 ms | Fine. The frame-time filter covers the gaps |
| > 30 ms | Slow. Consider raising `Prediction (seconds)` slightly |

Remember this is an **on-change** sensor: a long average interval mostly reflects time the
hinge spent still. The "recent event rate" readout is the one that tells you whether the
sensor keeps up while you actually move it.

### Largest discontinuity

A jump much larger than the resolution means a **dead zone** — a range where the sensor
stops reporting and then catches up. If you find one, note the angle: you may want to
place the concealment window over it, since a dead zone is a spot where the animation will
inevitably lurch.

---

## The handoff point — learned, not configured

There is no wizard step for this because it is not something you can measure by hand.

Every time the app's window moves between displays it records the fold progress at that
instant and keeps a running median. After **two or three folds** it knows where your
device swaps panels and the concealment window centres on it automatically.

Check it on the **Sensors** tab: *Measured handoff*. Until it appears, the app uses a
fallback of `p = 0.58` and labels it as a fallback in the UI.

To retrain it: **Tuning → Reset to Apple-like defaults** clears tuning but not the learned
handoff; to clear that too, clear app storage and re-calibrate.

---

## After calibration

Go to **Transition**, import a cover screenshot and an unfolded screenshot, and drag the
virtual hinge slider through the full range. Tune there before touching the physical hinge
— it is far faster, and the simulator feeds the identical pipeline, so anything that looks
right on the slider will look right on the hinge.

Then fold the phone and check the two things the simulator cannot show you:

1. Does the panel swap read as continuous?
2. Does P99 frame time stay under your budget (8.33 ms at 120 Hz)?

If (1) fails, let it learn the handoff over a few more folds, then try the
**Safe handoff** preset. If (2) fails, drop shader quality to `MEDIUM`.
