package dev.foldphase.renderer

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import dev.foldphase.core.SceneMapping
import dev.foldphase.engine.FoldVisualState
import dev.foldphase.engine.ShaderQuality

/** How the fold transition is actually being drawn on this device. */
enum class RenderPath {
    /** The AGSL shader — hinge-directed blur, the full effect. */
    SHADER,

    /** Platform-only fallback. Uniform blur instead of hinge-directed. */
    FALLBACK,

    /** Nothing to draw yet: no cover texture has been supplied. */
    NO_TEXTURE,
}

/**
 * Draws the fold transition between two scene textures.
 *
 * ## Why the state arrives as a [State] rather than as a value
 *
 * The lambda passed to `drawBehind` runs in the **draw phase**, so reading
 * `visualState.value` inside it invalidates only drawing — not composition, not layout.
 * That is the difference between a frame costing a few hundred microseconds and it
 * costing a recomposition of the whole subtree at 120 Hz.
 *
 * Taking a plain `FoldVisualState` parameter would invalidate composition on every sensor
 * frame. This signature exists to make that mistake impossible.
 *
 * ## Automatic fallback
 *
 * If AGSL fails to compile on this device, [FoldFallbackSurface] is rendered instead —
 * degraded, but never a crash or a black screen. [onPathResolved] reports which path was
 * taken so the UI can say so plainly rather than leaving the user guessing.
 */
@Composable
fun FoldTransitionSurface(
    visualState: State<FoldVisualState>,
    coverTexture: Bitmap?,
    innerTexture: Bitmap?,
    sceneMapping: SceneMapping,
    modifier: Modifier = Modifier,
    quality: ShaderQuality = ShaderQuality.HIGH,
    hingeFalloff: Float = 1.7f,
    onPathResolved: (RenderPath, String?) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current.density

    // One program for the lifetime of the composable. Compiling a RuntimeShader is
    // expensive; `remember` makes "compile once" structural rather than a convention
    // someone has to remember to follow. Construction cannot throw — see FoldShaderProgram.
    val program = remember { FoldShaderProgram(quality) }

    if (!program.isSupported) {
        onPathResolved(RenderPath.FALLBACK, program.compileError)
        FoldFallbackSurface(
            visualState = visualState,
            coverTexture = coverTexture,
            innerTexture = innerTexture,
            sceneMapping = sceneMapping,
            modifier = modifier,
        )
        return
    }

    val brush = remember(program, program.runtimeShader) {
        // Non-null here: isSupported was checked above and the shader only changes with
        // quality, which recreates the brush through this same key.
        ShaderBrush(program.runtimeShader!!)
    }

    program.quality = quality
    program.hingeFalloff = hingeFalloff
    program.setCoverTexture(coverTexture)
    program.setInnerTexture(innerTexture)

    onPathResolved(
        if (program.isReady) RenderPath.SHADER else RenderPath.NO_TEXTURE,
        null,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // State read deferred to the draw phase — see the note above.
                val state = visualState.value

                if (!program.isReady) {
                    // No texture bound yet. Black is the only safe thing to show, and it
                    // is what the very start of the transition looks like in any case.
                    drawRect(Color.Black)
                    return@drawBehind
                }

                program.apply(
                    state = state,
                    mapping = sceneMapping,
                    widthPx = size.width,
                    heightPx = size.height,
                    densityDp = density,
                )
                drawRect(brush = brush)
            },
    )
}
