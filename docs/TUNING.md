# Tuning guide

Every animation constant lives in one place — `FoldTuning` — and the Tuning tab mutates it
live. A change takes effect on the **next frame**: no recompile, no restart. That is
deliberate, because tuning this effect is an iterative visual process and a loop that
costs a build is a loop nobody finishes.

---

## Method

Tune in the **simulator** first, on the Transition tab. The virtual hinge feeds the
identical pipeline as the real sensor, so anything that looks right on the slider will
look right on the hinge — and you can hold `p = 0.54` for a minute while you stare at it,
which you cannot do with your hands.

Then verify on the physical hinge. The only two things the simulator genuinely cannot show
you are the panel handoff and real frame timings.

A useful sequence:

1. Load the **Exaggerated (dev)** preset. Everything is obvious, so you can see what each
   slider actually does.
2. Return to **Apple-like** and adjust one property at a time.
3. Sweep the slider slowly across the whole range after every change. Most mistakes are
   invisible at the extremes and glaring at `p ≈ 0.5`.

---

## The parameters, in the order worth touching them

### Phase boundaries

| Slider | Effect | Watch for |
| --- | --- | --- |
| **Animation start (deadband end)** | Below this, nothing moves | Too low → UI wobbles when the phone is shut. Too high → the start of the fold feels dead |
| **Outer fade start / end** | When the cover layer loses dominance | End too early and you get a gap where neither layer is legible |
| **Inner fade start / end** | When the inner layer becomes perceptible | Start too late and the inner display appears to "arrive" rather than settle |
| **Transition overlap** | Width of the dissolve | Too little reads as a cut; too much makes both layers simultaneously legible, which destroys the illusion |

### Handoff concealment — the important ones

| Slider | Effect |
| --- | --- |
| **Handoff centre (fallback)** | Only used until the app has *measured* the real swap point on your device. Once *Measured handoff* appears on the Sensors tab, this is ignored |
| **Handoff half-width** | How much of the fold the concealment covers. Wider = safer, but more of the fold spent defocused |
| **Black handoff opacity** | Default **0.12** |

On that default: the research found Apple's transition is a *see-through* defocused
crossfade, not a blackout (`RESEARCH.md` §1.2 B). A blackout is the safe engineering answer
but it costs the "one continuous interface" impression that is the whole point.

**If your device visibly flashes at the swap**, in order:

1. Fold a few more times — let `HandoffLearner` centre the window properly. This fixes it
   more often than anything else.
2. Widen **Handoff half-width** to ~0.15.
3. Raise **Black handoff opacity**, or just load the **Safe handoff** preset.

Do not jump straight to (3). A mis-centred window is the usual cause, and black is
covering for it rather than fixing it.

### Magnitudes

| Slider | Notes |
| --- | --- |
| **Max blur at hinge (dp)** | The headline effect. The reference blurs where the screens meet |
| **Max blur at outer edge (dp)** | Keep this **well below** the hinge value. Equal values give a uniform blur, which is exactly what the reference is not |
| **Hinge falloff** | How tightly blur hugs the seam. Higher = tighter |
| **Cover scale amount** | 1–3% is the reference band. Above ~5% it reads as a zoom |
| **Inner start scale** | 1.04–1.08. Above ~1.15 the inner display looks like it is falling toward you |
| **Wallpaper scale amount** | Should exceed cover scale — that differential *is* the parallax depth cue |
| **Vignette / Hinge shadow** | Depth around the fold. Overdone reads as a photo filter |
| **Edge illumination** | A thin specular line on the fold. Sells "a physical panel edge" cheaply. Not from the reference material — an addition. Set to 0 to disable |
| **Perspective** | Keep low. Strong perspective reads as gimmicky |
| **Desaturation** | Slight. Fully draining colour reads as a "system" effect |
| **Dissolve softness** | Width of the gradient over which the swap happens |

### Sensor filtering

This is the jitter-versus-lag tradeoff, and it is the one place where "just smooth it
more" is actively wrong — lag here destroys the direct-manipulation feel that the entire
project exists to produce.

| Slider | Lower | Higher |
| --- | --- | --- |
| **One Euro min cutoff** | Steadier when held, more lag when slow | Snappier, admits more jitter |
| **One Euro beta** | More lag on fast motion | Less lag on fast motion, more jitter |
| **Prediction (seconds)** | — | Buys back latency, but over-prediction overshoots when the hinge stops abruptly, which reads as a spring the reference does not have |

Set these from **measurement, not feel**: the Sensors tab reports your device's noise floor
(σ) and update interval. Then:

| Slider | Rule |
| --- | --- |
| **Hysteresis: enter (°/s)** | Comfortably above your noise-driven velocity, below your slowest deliberate movement |
| **Hysteresis: exit (°/s)** | Must stay **below** enter, or direction will chatter |
| **Direction dwell (ms)** | ~3 frames. Longer rejects more spikes but delays genuine reversals |
| **Closed deadband** | Roughly 3σ expressed in progress units |

### Rendering

**Shader quality** — `HIGH` 13 taps / `MEDIUM` 9 / `LOW` 5. Tap count is the dominant cost
of the whole effect. The P95/P99 readout is directly above the selector; change one, read
the other.

---

## Presets

| Preset | For |
| --- | --- |
| **Apple-like (default)** | Derived from `RESEARCH.md`. Start here |
| **Safe handoff** | Device flashes at the swap and learning did not fix it. Trades see-through for a guaranteed-invisible discontinuity |
| **Subtle** | Nearly a straight crossfade. Useful as a perceptual baseline for A/B |
| **Exaggerated (dev)** | Everything obvious. For learning what the sliders do, not for use |

---

## Judging the result

The acceptance criterion is not "does an animation play". It is whether it feels like the
UI is physically attached to the hinge. Concretely:

- Move the hinge 5° and back. Did the image move *with your hand*, or after it?
- Hold at `p ≈ 0.5` for ten seconds. Does anything drift, shimmer, or settle?
- Reverse mid-fold. Does it retrace, or does it re-animate?
- Watch the seam at the handoff, not the whole screen. That is where it either works or
  does not.

If it feels like a Samsung phone playing an animation, something is decoupled from the
hinge. Check the direction thresholds first — chattering direction is the most common cause
of "played, not scrubbed".
