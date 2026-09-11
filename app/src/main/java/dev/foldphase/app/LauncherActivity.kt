package dev.foldphase.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.foldphase.app.ui.FoldPhaseTheme
import dev.foldphase.launcher.LauncherSceneRenderer
import dev.foldphase.launcher.sampleHomeScene

/**
 * Level B (brief §2) / Milestone 5 (brief §29): launcher mode.
 *
 * ## Why being the launcher helps
 *
 * A normal app can only transform *its own* content. When FoldPhase is the home app, its
 * own content **is** the home screen, so the transition transforms real launcher elements
 * rather than a screenshot of somebody else's. That is what makes
 * `cover launcher state → fold transition → inner launcher state` a single continuous
 * scene rather than a crossfade between two pictures of one.
 *
 * Scope is deliberately narrow, per the brief: this is not a Nova competitor. It renders
 * a wallpaper, an icon grid and a dock from a shared [dev.foldphase.launcher.HomeScene],
 * and preserves page position across the fold. Everything it does exists to make the fold
 * convincing.
 *
 * `configChanges` covers the fold-related axes so the launcher is not torn down and
 * rebuilt at the exact moment it is supposed to be animating.
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var controller: FoldController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = foldController
        controller.useRealSensor()
        controller.reportDisplay(display)

        setContent {
            FoldPhaseTheme {
                // Scene is remembered at the application level in a fuller implementation;
                // the sample scene keeps this build self-contained and deterministic.
                val scene = remember { sampleHomeScene() }
                LauncherSceneRenderer(
                    scene = scene,
                    visualState = controller.visualState,
                    sceneMapping = controller.engine.sceneMapping,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.reportDisplay(display)
    }

    override fun onResume() {
        super.onResume()
        controller.useRealSensor()
        controller.reportDisplay(display)
    }
}
