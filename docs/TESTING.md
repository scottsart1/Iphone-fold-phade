# Testing checklist

## Automated (run in CI, no device)

```bash
./gradlew test
```

50 tests. Each corresponds to a specific promise, not to coverage for its own sake.

| Area | What is pinned down |
| --- | --- |
| `FoldAnimationEngineTest` | Purity (100 evaluations at the same progress are identical) · closing is the exact reverse of opening across the whole range · deadband is a true identity · endpoints are exact · no property jumps discontinuously · viewport grows monotonically · black veil returns to zero at both ends · **the default preset's veil stays below 0.25** · velocity changes concealment but never geometry · all outputs in range · every preset valid |
| `CurvesTest` | Every easing shape is monotonic and hits its endpoints · degenerate and inverted remap ranges cannot produce NaN · `cubicBezier` matches a known identity control set · `bumpAround` is centred and bounded · `smootherstep` has zero slope at both ends |
| `SceneMappingTest` | Cover rect sits on the correct half · viewport interpolation spans cover→inner · **signed hinge distance matches the shader's sign convention** · extreme aspect ratios stay bounded |
| `OneEuroFilterTest` | Suppresses jitter on a still signal (< 0.15° deviation) · tracks a 180°-in-300 ms snap with < 18° lag · survives duplicate timestamps |
| `VelocityEstimatorTest` | Estimates a constant slope · sign distinguishes opening from closing · **decays to zero when samples stop** · quantised input does not spike |
| `FoldStateMachineTest` | **500 noise samples produce zero direction flips** · commits on sustained velocity in both directions · reversal picked up within the dwell · enters handoff state in the window · error recovery latches |
| `HandoffLearnerTest` | Returns nothing before observing · learns from display changes · **median rejects an outlier** · ignores implausible observations at the extremes · seeding works |
| `HingeCalibrationTest` | Normalises and clamps · handles a non-0/180 range · degenerate range cannot produce NaN · `angleFor` inverts `progressFor` · deadband bounded |

Two of these tests exist specifically as regression guards against plausible future
mistakes:

- *"apple-like preset keeps the black veil subtle"* — stops someone fixing a device flash
  by turning the default into a blackout, quietly abandoning the reference behaviour.
- *"signed hinge distance is positive on the cover half"* — the Kotlin and GPU sides once
  disagreed on this sign. The symptom is a mirrored effect at runtime, which is easy to
  mistake for a tuning problem.

---

## On-device checklist

Brief §24. Tick these on the actual Fold 7.

### Hinge coupling — the core claim

- [ ] **Extremely slow opening** (10 s end to end) — smooth, no stepping
- [ ] **Extremely fast opening** (snap) — smooth, no tearing, no overshoot
- [ ] **Stop at 20°** — animation holds exactly, indefinitely
- [ ] **Stop at 45°** — holds
- [ ] **Stop at 90°** — holds
- [ ] **Stop at 135°** — holds
- [ ] **Reverse halfway** — reverses immediately, retraces the same states
- [ ] **Wobble near a threshold** — no direction chatter, no flicker
- [ ] **Rapid open-close-open** — tracks without lag accumulation
- [ ] Fully open → rests clean, no residual blur or dim
- [ ] Fully closed → rests clean

### The handoff — where this succeeds or fails

- [ ] No white flash
- [ ] No black flash beyond the intended veil
- [ ] No Samsung home-screen flash
- [ ] No wallpaper discontinuity
- [ ] No single frame with the wrong orientation or layout
- [ ] *Measured handoff* appears on the Sensors tab after 2–3 folds
- [ ] Handoff still concealed after it has learned

### System states

- [ ] Screen initially off, then unfold
- [ ] Lockscreen active
- [ ] Always-on display
- [ ] An app open
- [ ] Keyboard open
- [ ] Split screen
- [ ] Popup window
- [ ] Samsung DeX
- [ ] Landscape
- [ ] Portrait
- [ ] Flex Mode (held at ~90°)
- [ ] Battery saver on
- [ ] Forced 60 Hz
- [ ] 120 Hz (adaptive)

### Resilience

- [ ] Force-stop the app mid-transition → no stuck overlay
- [ ] Kill the overlay service mid-transition → overlay removed
- [ ] Revoke overlay permission while running → torn down within ~2 s
- [ ] Reboot → state restored, calibration retained
- [ ] Change default launcher away and back
- [ ] Turn the screen off mid-fold → overlay removed

### Stability

- [ ] **100 consecutive open/close cycles.** Watch for: drift in the learned handoff,
      growth in dropped frames, memory growth, the app needing a restart.
      Export the CSV afterwards and check the trace for discontinuities.

### Performance

Read from the Tuning tab while folding:

- [ ] Average frame time < 8.33 ms at 120 Hz
- [ ] P95 < 8.33 ms
- [ ] P99 < 12 ms (one late frame per hundred is not visible; ten are)
- [ ] Dropped frames < 1%
- [ ] Effective rate ≈ display rate

If P99 misses: drop shader quality one step and re-measure. `HIGH`→`MEDIUM` roughly
halves the sampling cost.

### Power (brief §23)

- [ ] Leave the phone open and idle for 1 hour with the app foregrounded. Check Battery
      usage. The Choreographer loop should be **stopped** — only the on-change sensor
      stays registered.
- [ ] Same, backgrounded.
- [ ] Same with overlay mode armed. Expect a small but non-zero cost from the foreground
      service; if it is large, something is keeping the loop alive.

---

## Getting diagnostics out

**Sensors → Export CSV** writes the hinge trace:
`timestamp_nanos, elapsed_ms, raw_angle_deg, filtered_angle_deg, velocity_deg_per_sec, progress, direction, state`

**Sensors → Sensor report** writes device identity, the full sensor inventory with every
property, and observed hinge statistics. This is the artefact to keep if something behaves
oddly — it contains everything needed to tell whether the problem is the app or the
hardware.

```bash
adb shell run-as dev.foldphase.app.debug ls cache/exports
adb exec-out run-as dev.foldphase.app.debug cat cache/exports/<file> > local.csv
```

Plot `elapsed_ms` against `raw_angle_deg` and `filtered_angle_deg` together. The gap
between them *is* the jitter-versus-lag tradeoff — if filtered lags visibly during fast
motion, raise `One Euro beta`; if it shimmers while held, lower `One Euro min cutoff`.
