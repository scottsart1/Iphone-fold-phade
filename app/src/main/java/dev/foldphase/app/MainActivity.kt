package dev.foldphase.app

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.foldphase.app.ui.CalibrationScreen
import dev.foldphase.app.ui.DiagnosticsScreen
import dev.foldphase.app.ui.FoldPhaseTheme
import dev.foldphase.app.ui.OverlayScreen
import dev.foldphase.app.ui.TransitionScreen
import dev.foldphase.app.ui.TuningScreen
import dev.foldphase.sensors.FoldingFeatureMonitor
import kotlinx.coroutines.launch

/**
 * The development host: diagnostics, calibration, the transition preview and the tuning
 * panel.
 *
 * ## Configuration changes (brief §19)
 *
 * `android:configChanges` in the manifest declares that this activity handles screen
 * size, density, orientation and layout changes itself, so folding does **not** destroy
 * and recreate it. That matters because a recreation mid-transition would drop the very
 * frames the transition is made of.
 *
 * Even so, nothing important is stored in the activity: the pipeline, engine, learner and
 * textures all live in the application-scoped [FoldController], so a recreation forced by
 * something outside our control — the system killing the process, a display added or
 * removed — costs nothing but the current tab index.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: FoldController
    private var coverPresenter: CoverDisplayPresenter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = foldController
        controller.useRealSensor()

        coverPresenter = CoverDisplayPresenter(this, this).also { it.observe() }

        // Watch the fold geometry so the scene mapping tracks where the fold line
        // actually is, rather than assuming dead centre.
        val monitor = FoldingFeatureMonitor(this)
        lifecycleScope.launch {
            monitor.geometry.collect { geometry ->
                if (geometry.hasFoldingFeature) {
                    controller.updateSceneMapping(
                        controller.engine.sceneMapping.copy(
                            hingeAxis = geometry.hingeAxis,
                            hingePosition = geometry.hingePositionFraction,
                        ),
                    )
                }
            }
        }

        reportCurrentDisplay()

        setContent {
            FoldPhaseTheme {
                MainScaffold(
                    controller = controller,
                    coverPresenter = coverPresenter,
                    onRequestOverlayPermission = ::requestOverlayPermission,
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A fold usually arrives here. Telling the controller which display we are on is
        // what lets HandoffLearner measure the real panel-swap progress.
        reportCurrentDisplay()
    }

    override fun onResume() {
        super.onResume()
        controller.useRealSensor()
        reportCurrentDisplay()
    }

    private fun reportCurrentDisplay() {
        controller.reportDisplay(display)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}

private enum class MainTab(val label: String) {
    TRANSITION("Transition"),
    DIAGNOSTICS("Sensors"),
    CALIBRATION("Calibrate"),
    TUNING("Tuning"),
    OVERLAY("Overlay"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    controller: FoldController,
    coverPresenter: CoverDisplayPresenter?,
    onRequestOverlayPermission: () -> Unit,
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = remember { MainTab.entries }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("FoldPhase") })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = {
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
            when (tabs[tabIndex]) {
                MainTab.TRANSITION -> TransitionScreen(controller)
                MainTab.DIAGNOSTICS -> DiagnosticsScreen(controller)
                MainTab.CALIBRATION -> CalibrationScreen(controller)
                MainTab.TUNING -> TuningScreen(controller)
                MainTab.OVERLAY -> OverlayScreen(
                    controller = controller,
                    coverPresenter = coverPresenter,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                )
            }
        }
    }
}
