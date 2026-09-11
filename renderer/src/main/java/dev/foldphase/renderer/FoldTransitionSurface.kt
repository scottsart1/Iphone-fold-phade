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

/**
 * Draws the fold transition between two scene textures.
 *
 * ## Why the state arrives as a [State] rather than as a value
 *
 * The lambda passed to [drawBehind] runs in the **draw phase**, so reading
 * `visualState.value` inside it invalidates only drawing — not composition, not layout.
 * That is the difference between a frame costing a few hundred microseconds and it
 * costing a recomposition of the whole subtree at 120 Hz (brief §22: "no unnecessary
 * Compose recompositions").
 *
 * Taking a plain `FoldVisualState` parameter instead would invalidate composition on
 * every single sensor frame. This signature exists to make that mistake impossible.
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
) {
    val density = LocalDensity.current.density

    // One program for the lifetime of the composable. Compiling a RuntimeShader is
    // expensive; `remember` makes "compile once" structural rather than a convention
    // someone has to remember to follow.
    val program = remember { FoldShaderProgram(quality) }
    val brush = remember(program) { ShaderBrush(program.runtimeShader) }

    program.quality = quality
    program.hingeFalloff = hingeFalloff
    program.setCoverTexture(coverTexture)
    program.setInnerTexture(innerTexture)

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
