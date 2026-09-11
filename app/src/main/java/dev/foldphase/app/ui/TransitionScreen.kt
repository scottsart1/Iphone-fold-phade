package dev.foldphase.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.foldphase.app.FoldController
import dev.foldphase.launcher.LauncherSceneRenderer
import dev.foldphase.launcher.sampleHomeScene
import dev.foldphase.renderer.FoldTransitionSurface
import dev.foldphase.renderer.SceneTextureCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class PreviewSource { SCREENSHOTS, LAUNCHER_SCENE }

/**
 * Milestones 2 and 3 (brief §29): the virtual prototype and the screenshot POC.
 *
 * The brief is explicit in §16 that a great screenshot-driven animation is worth more
 * than a mediocre complete launcher, so this screen is the main tuning surface. It
 * supports two texture sources:
 *
 * - **Screenshots** — supply a cover and an inner screenshot and the shader interpolates
 *   between them. This is the fastest way to judge the effect against real content.
 * - **Launcher scene** — the semantic [dev.foldphase.launcher.HomeScene], whose icons
 *   genuinely travel between grid positions rather than crossfading.
 *
 * Both are driven by the same [dev.foldphase.sensors.FoldProgressSource] abstraction, so
 * the virtual slider and the real hinge exercise identical code (brief §27).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransitionScreen(controller: FoldController, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val progress by controller.progress.collectAsStateWithLifecycle()
    val tuning by controller.tuning.collectAsStateWithLifecycle()
    val usingVirtual by controller.usingVirtualHinge.collectAsStateWithLifecycle()
    val activeQuality by controller.activeQuality.collectAsStateWithLifecycle()

    var source by remember { mutableStateOf(PreviewSource.SCREENSHOTS) }
    var sliderProgress by remember { mutableFloatStateOf(0f) }
    var sweepJob by remember { mutableStateOf<Job?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var textureVersion by remember { mutableIntStateOf(0) }

    val scene = remember { sampleHomeScene() }

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importMessage = controller.textureCache
                    .import(uri, SceneTextureCache.Slot.COVER)
                    .fold({ "Cover screenshot imported" }, { "Import failed: ${it.message}" })
                textureVersion++
            }
        }
    }
    val pickInner = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importMessage = controller.textureCache
                    .import(uri, SceneTextureCache.Slot.INNER)
                    .fold({ "Inner screenshot imported" }, { "Import failed: ${it.message}" })
                textureVersion++
            }
        }
    }

    // Keep the slider in step with the pipeline when the hinge is driving, so switching
    // between real and virtual does not jump.
    LaunchedEffect(usingVirtual) {
        if (!usingVirtual) sliderProgress = progress.progress
    }

    /** Run a scripted sweep at a given duration (brief §21: test open/close, slow-mo). */
    fun sweep(from: Float, to: Float, durationMs: Int) {
        sweepJob?.cancel()
        controller.useVirtualHinge()
        sweepJob = scope.launch {
            val startNanos = System.nanoTime()
            while (isActive) {
                val elapsed = (System.nanoTime() - startNanos) / 1_000_000f
                val t = (elapsed / durationMs).coerceIn(0f, 1f)
                sliderProgress = from + (to - from) * t
                controller.setVirtualProgress(sliderProgress)
                if (t >= 1f) break
                // ~120 Hz. The pipeline still renders on Choreographer; this only sets
                // the target, exactly as the real sensor would.
                delay(8)
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // ---- The effect itself --------------------------------------------------
        SectionCard("Preview") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.9f)
                    .padding(top = 4.dp),
            ) {
                when (source) {
                    PreviewSource.SCREENSHOTS -> {
                        // textureVersion forces a re-read after an import; without it the
                        // remembered bitmaps would be stale.
                        @Suppress("UNUSED_EXPRESSION")
                        textureVersion
                        FoldTransitionSurface(
                            visualState = controller.visualState,
                            coverTexture = controller.textureCache.coverTexture,
                            innerTexture = controller.textureCache.innerTexture,
                            sceneMapping = controller.engine.sceneMapping,
                            quality = activeQuality,
                            hingeFalloff = tuning.hingeFalloff,
                            onPathResolved = { path, detail ->
                                controller.reportRenderPath(path.name, detail)
                            },
                        )
                    }
                    PreviewSource.LAUNCHER_SCENE -> {
                        LauncherSceneRenderer(
                            scene = scene,
                            visualState = controller.visualState,
                            sceneMapping = controller.engine.sceneMapping,
                        )
                    }
                }
            }
            if (source == PreviewSource.SCREENSHOTS &&
                controller.textureCache.coverTexture == null
            ) {
                Text(
                    "Import a cover screenshot below to see anything here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---- Source -------------------------------------------------------------
        SectionCard("Scene source") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == PreviewSource.SCREENSHOTS,
                    onClick = { source = PreviewSource.SCREENSHOTS },
                    label = { Text("Screenshots") },
                )
                FilterChip(
                    selected = source == PreviewSource.LAUNCHER_SCENE,
                    onClick = { source = PreviewSource.LAUNCHER_SCENE },
                    label = { Text("Launcher scene") },
                )
            }
            if (source == PreviewSource.SCREENSHOTS) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = {
                        pickCover.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }) { Text("Cover screenshot") }
                    OutlinedButton(onClick = {
                        pickInner.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }) { Text("Inner screenshot") }
                }
                Text(
                    "Take one screenshot on the cover display and one unfolded, then " +
                        "import both. Textures are decoded once and reused — never " +
                        "per frame.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                importMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    "The launcher scene interpolates icon positions between the 4-column " +
                        "cover grid and the 6-column inner grid, matched by item id. " +
                        "Icons travel; they do not crossfade.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---- Virtual hinge ------------------------------------------------------
        SectionCard("Virtual hinge") {
            Readout("Progress", progress.progress.fmt(4))
            Readout("Angle", "${progress.filteredAngleDeg.fmt(2)}°")
            Readout("State", progress.state.name)
            TuningSlider(
                label = "0° ———————— 180°",
                value = sliderProgress,
                onValueChange = {
                    sweepJob?.cancel()
                    sliderProgress = it
                    if (!usingVirtual) controller.useVirtualHinge()
                    controller.setVirtualProgress(it)
                },
                valueRange = 0f..1f,
                decimals = 4,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                AssistChip(
                    onClick = { sweep(0f, 1f, 900) },
                    label = { Text("Test opening") },
                )
                AssistChip(
                    onClick = { sweep(1f, 0f, 900) },
                    label = { Text("Test closing") },
                )
                AssistChip(
                    onClick = { sweep(0f, 1f, 6000) },
                    label = { Text("Slow-motion open") },
                )
                AssistChip(
                    onClick = { sweep(1f, 0f, 6000) },
                    label = { Text("Slow-motion close") },
                )
                AssistChip(
                    onClick = { sweep(0f, 1f, 220) },
                    label = { Text("Snap open") },
                )
            }
            Button(
                onClick = {
                    sweepJob?.cancel()
                    controller.useRealSensor()
                },
                enabled = usingVirtual && controller.hasHingeSensor,
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Back to real hinge sensor") }
        }

        // ---- Live state ---------------------------------------------------------
        SectionCard("Visual state at this progress") {
            val v = controller.visualState.value
            Readout("Cover alpha", v.coverAlpha.fmt(3))
            Readout("Cover scale", v.coverScale.fmt(4))
            Readout("Cover blur (hinge / edge)", "${v.coverBlurDp.fmt(1)} / ${v.coverEdgeBlurDp.fmt(1)} dp")
            Readout("Inner alpha", v.innerAlpha.fmt(3))
            Readout("Inner scale", v.innerScale.fmt(4))
            Readout("Inner blur (hinge / edge)", "${v.innerBlurDp.fmt(1)} / ${v.innerEdgeBlurDp.fmt(1)} dp")
            Readout("Cross dissolve", v.crossDissolve.fmt(3))
            Readout("Black overlay", v.blackOverlayAlpha.fmt(3))
            Readout("Vignette", v.vignetteIntensity.fmt(3))
            Readout("Hinge shadow", v.hingeShadowIntensity.fmt(3))
            Readout("Edge illumination", v.edgeIlluminationIntensity.fmt(3))
            Readout("Brightness (cover/inner)", "${v.coverBrightness.fmt(3)} / ${v.innerBrightness.fmt(3)}")
            Readout(
                "Viewport",
                "[${v.viewport.left.fmt(3)}, ${v.viewport.right.fmt(3)}] " +
                    "w=${v.viewport.width.fmt(3)}",
            )
        }
    }
}
