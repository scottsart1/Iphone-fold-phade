# Installing without a computer

Written for the case where you cannot run Android Studio or adb. Everything below happens
on the Z Fold 7 itself.

---

## Get the APK

A prebuilt, signed APK is committed to this repository at:

```
dist/foldphase-release.apk
```

On the phone, open the repository on github.com, navigate to `dist/`, tap the file, then
tap **Download raw file**. Chrome will warn that this file type can harm your device —
that warning appears for every APK; continue.

Then open it from your Downloads and allow Chrome (or your file manager) to install
unknown apps when prompted. That permission is per-app and you can revoke it afterwards
under **Settings → Apps → Special access → Install unknown apps**.

The app appears as **FoldPhase**.

---

## Is it safe to install?

You should ask this of any sideloaded APK. Concretely, for this one:

- It requests **no INTERNET permission**. It cannot send anything anywhere. You can verify
  this yourself after install: **Settings → Apps → FoldPhase → Permissions**.
- The only permissions it ever requests are `SYSTEM_ALERT_WINDOW`, foreground service and
  notifications — and all three are **opt-in**, used solely by the experimental overlay
  mode. The core app requests nothing.
- It is signed with a throwaway key generated for this build, not a distribution identity.
  That is why Android calls it an unknown app.

---

## First run, in order

### 1. Sensors tab

Check the big number at the top moves when you move the hinge.

- **It moves** → good, the hinge sensor is present and working.
- **"No hinge sensor found"** → the app will still run from the virtual hinge slider, but
  nothing will be physically coupled. Send me the sensor report (below).

Also check **Rendering → Path**:

| Value | Meaning |
| --- | --- |
| `SHADER` | The full effect, hinge-directed blur. What you want. |
| `NO_TEXTURE` | Working, but you have not imported screenshots yet. Normal at this point. |
| `FALLBACK` | The GPU rejected the shader. The app still works, degraded — see below. |

### 2. Just fold the phone a few times

You do **not** have to run the calibration wizard. Open it fully, close it fully, twice.
That is enough for the app to:

- learn your hinge's actual range (Sensors tab shows *provisional (auto-learned)*),
- learn where your device swaps physical panels (*Measured handoff* appears),
- learn both panels' real geometry (*Scene mapping: measured*).

Running the wizard on the **Calibrate** tab is more precise and takes a minute. It is
worth doing eventually, but the app is usable without it.

### 3. Transition tab

Take a screenshot of your home screen while folded, and another while unfolded. Import
both. Then drag the slider — this is where you can actually see the effect and judge it
without repeatedly folding the phone.

### 4. Fold for real

With screenshots imported, fold the phone and watch the seam.

---

## If something looks wrong

| Symptom | Fix |
| --- | --- |
| Effect is **mirrored** — content emerges from the wrong edge | **Tuning → Device geometry → Cover on left half**. This is a chassis property no Android API reports, so it is a coin-flip I could not verify. |
| **Flash** at the moment the screens swap | Fold a few more times first — the app is still learning your handoff point, and a mis-centred window is the usual cause. If it persists: **Tuning → Presets → Safe handoff**. |
| Animation **stutters** | The app steps quality down by itself after ~3 seconds of missed frames. If it still stutters, set **LOW** manually under Tuning → Rendering. |
| Effect feels **laggy** behind your hand | Tuning → Sensor filtering → raise **One Euro beta**. |
| UI **wobbles** when the phone is shut | Raise **Closed deadband**. |
| Path shows **FALLBACK** | The shader was rejected by your GPU. The app works but the blur is uniform rather than concentrated at the fold. Export a sensor report and send it — the compile error is in it, and it is fixable. |

---

## Sending me diagnostics

Go to **Sensors → Diagnostics report** and tap **Copy summary**. Then paste it into the
chat. That is the whole procedure — no files, no file manager, no adb.

The summary is about thirty lines: your device and Android build, the hinge sensor's full
specification, the angle range actually observed, which render path resolved, whether
calibration is measured or provisional, whether the panel handoff has been learned, both
panels' aspects, and frame timings. It is enough to diagnose nearly anything.

Other buttons on that card:

| Button | What it does |
| --- | --- |
| **Copy summary** | ~30 lines to the clipboard. Start here. |
| **Share** | Same text, via the system share sheet. |
| **Copy FULL report** | Every sensor on the device with all properties. Thousands of characters — only if asked. |
| **Share hinge CSV** | The raw trace, as a file, through the share sheet. |

Everything is also rendered on screen underneath, so you can read or screenshot it.

**Why not just grab the file?** Earlier builds only wrote to
`Android/data/dev.foldphase.app/cache/exports/`, which Android has blocked file managers
from browsing since Android 11 — so the file was unreachable on the phone that made it.
That was a design mistake; the clipboard path replaces it.

**Move the hinge first.** A summary copied before the hinge has moved will honestly report
"no samples recorded yet", which tells me much less. Fold the phone open and shut once,
then copy.

---

## Uninstalling

**Settings → Apps → FoldPhase → Uninstall.** It stores nothing outside its own sandbox, so
uninstalling removes everything.

If you tried launcher mode, set your launcher back first: **Settings → Apps → Choose
default apps → Home app → One UI Home**. Uninstalling while it is the active launcher is
recoverable — Android falls back to One UI Home — but changing it first is tidier.
