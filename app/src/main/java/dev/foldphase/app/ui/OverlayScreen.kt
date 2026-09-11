package dev.foldphase.app.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldphase.app.CoverDisplayPresenter
import dev.foldphase.app.FoldController
import dev.foldphase.overlay.FoldOverlayService

/**
 * Level C (brief §2) and the system-wide experiment (brief §17).
 *
 * This screen is as much documentation as it is control surface. The brief's §31 asks
 * that impossible things be named rather than faked, and the honest position is subtle
 * enough that it belongs in front of the user, not only in a markdown file: the overlay
 * genuinely helps, and it genuinely cannot do the thing people assume it does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayScreen(
    controller: FoldController,
    coverPresenter: CoverDisplayPresenter?,
    onRequestOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var overlayRunning by remember { mutableStateOf(false) }
    val hasPermission = Settings.canDrawOverlays(context)
    val coverStatus by (coverPresenter?.status?.collectAsStateWithLifecycle()
        ?: remember { androidx.compose.runtime.mutableStateOf(null) })
    val isPresenting by (coverPresenter?.isPresenting?.collectAsStateWithLifecycle()
        ?: remember { androidx.compose.runtime.mutableStateOf(false) })

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        SectionCard("What this mode can actually do") {
            Text(
                "When you fold while another app is open, this overlay can dim, cast a " +
                    "hinge shadow, run the reveal gradient and fade to the handoff — all " +
                    "driven by the same hinge-coupled engine as everywhere else.",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("What it cannot do", style = MaterialTheme.typography.titleSmall)
            Text(
                "It cannot show the other app's content warped or blurred, because " +
                    "Android does not let an ordinary app read another app's pixels. " +
                    "Doing that needs MediaProjection (which shows a recording " +
                    "indicator and asks for consent every session), privileged access, " +
                    "or root.\n\n" +
                    "This app does not fake its way around that with an Accessibility " +
                    "Service. See docs/LIMITATIONS.md for the full account.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("Overlay permission") {
            Readout("SYSTEM_ALERT_WINDOW", if (hasPermission) "granted" else "not granted")
            if (!hasPermission) {
                Text(
                    "Required for the overlay mode only. Nothing else in the app needs it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Button(onClick = onRequestOverlayPermission) { Text("Grant permission") }
            }
        }

        SectionCard("Overlay service") {
            Readout("State", if (overlayRunning) "running" else "stopped")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(
                    enabled = hasPermission && !overlayRunning,
                    onClick = {
                        FoldOverlayService.start(context)
                        overlayRunning = true
                    },
                ) { Text("Start") }
                OutlinedButton(
                    enabled = overlayRunning,
                    onClick = {
                        FoldOverlayService.stop(context)
                        overlayRunning = false
                    },
                ) { Text("Stop") }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("Failsafes", style = MaterialTheme.typography.titleSmall)
            Text(
                "The overlay is removed by any of: a 600 ms render heartbeat timeout, a " +
                    "6 s absolute deadline nothing can extend, screen off, an unexpected " +
                    "configuration change, shutdown, permission revocation (polled every " +
                    "2 s), service destruction, or process death — the last of which " +
                    "WindowManager handles for us.\n\n" +
                    "The window is also FLAG_NOT_TOUCHABLE, so even a stuck one would " +
                    "pass every touch straight through to whatever is underneath.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard("Cover display presentation") {
            Readout(
                "Capability",
                coverPresenter?.statusDescription() ?: "presenter unavailable",
            )
            Readout("Presenting", if (isPresenting) "yes" else "no")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(
                    enabled = coverPresenter != null && !isPresenting,
                    onClick = { coverPresenter?.start() },
                ) { Text("Present on cover") }
                OutlinedButton(
                    enabled = isPresenting,
                    onClick = { coverPresenter?.stop() },
                ) { Text("Stop") }
            }
            Text(
                "Concurrent display only reports AVAILABLE while the device is unfolded, " +
                    "and starting a session shows a system dialog we cannot customise. " +
                    "So this is an enhancement, not the mechanism the transition relies " +
                    "on — continuity comes from both panels evaluating the same function " +
                    "of hinge progress, which needs no special access at all.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionCard("Launcher mode") {
            Text(
                "Setting FoldPhase as your home app lets it render the home screen " +
                    "itself, so the transition transforms real launcher elements instead " +
                    "of a screenshot. Icons travel between the cover and inner grids " +
                    "rather than crossfading.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    context.startActivity(
                        android.content.Intent(Settings.ACTION_HOME_SETTINGS),
                    )
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Open home app settings") }
            Text(
                "This launcher is deliberately minimal — it exists to make the fold " +
                    "transition convincing, not to replace a full launcher. Read " +
                    "docs/LIMITATIONS.md before setting it as your default.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
