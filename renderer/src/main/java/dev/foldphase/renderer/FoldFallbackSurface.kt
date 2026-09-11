package dev.foldphase.renderer

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.foldphase.core.Curves
import dev.foldphase.core.SceneMapping
import dev.foldphase.engine.FoldVisualState
import kotlin.math.roundToInt

/**
 * The no-AGSL fallback renderer.
 *
 * ## Why this exists
 *
 * `RuntimeShader` compilation can be rejected by a specific GPU driver even when the
 * source is valid per spec. On a device the author cannot test, that is a real
 * possibility, and the consequence of not handling it is a black screen or a crash rather
 * than a degraded effect.
 *
 * So this renders the same [FoldVisualState] using only **platform** APIs that are far
 * likelier to work: `graphicsLayer` transforms, `RenderEffect.createBlurEffect` (a stock
 * Android blur, not a custom shader), a `ColorMatrix` for brightness and saturation, and
 * ordinary gradient draws for the vignette and hinge shadow.
 *
 * ## What is lost, honestly
 *
 * The blur becomes **uniform** rather than hinge-directed, because a stock blur effect
 * takes one radius for the whole layer. Since the hinge-directed gradient is the single
 * most distinctive property of the reference animation, this fallback is meaningfully
 * less convincing — it is a safety net, not a second implementation.
 *
 * Two things partly compensate, and both are cheap:
 * - The blur radius is driven by the *hinge-edge* value, so the peak strength matches.
 * - A hinge-anchored gradient scrim is still drawn over the top, which preserves the
 *   "darkness gathers at the fold" cue even though the defocus itself is flat.
 *
 * Everything else — viewport expansion, cross-dissolve, scale, dim, desaturation, veil,
 * vignette — behaves identically, because all of it comes from the same engine output.
 */
@Composable
fun FoldFallbackSurface(
    visualState: State<FoldVisualState>,
    coverTexture: Bitmap?,
    innerTexture: Bitmap?,
    sceneMapping: SceneMapping,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val cover = coverTexture?.takeIf { !it.isRecycled }?.asImageBitmap()
    val inner = innerTexture?.takeIf { !it.isRecycled }?.asImageBitmap()

    Box(
        modifier = modifier
            .fillMaxSize()
            // The blur lives on the layer rather than in the draw lambda because
            // RenderEffect is a layer property. Reading progress here does cost a
            // recomposition per frame, which is the price of not having a shader.
            .graphicsLayer {
                val state = visualState.value
                val radiusPx = maxOf(state.coverBlurDp, state.innerBlurDp) * density
                renderEffect = if (radiusPx > 0.5f) {
                    RenderEffect
                        .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else {
                    null
                }
            }
            .drawBehind {
                val state = visualState.value
                drawRect(Color.Black)

                // Cover layer, then inner layer over it at the dissolve weight. Both are
                // drawn through the same viewport, so the portal model still holds and
                // the panel handoff still lines up.
                if (cover != null) {
                    drawSceneLayer(
                        image = cover,
                        sceneRect = sceneMapping.coverSceneRect,
                        state = state,
                        alpha = 1f,
                        scale = state.coverScale,
                        brightness = state.coverBrightness,
                        saturation = state.coverSaturation,
                    )
                }
                if (inner != null && state.crossDissolve > 0.002f) {
                    drawSceneLayer(
                        image = inner,
                        sceneRect = sceneMapping.innerSceneRect,
                        state = state,
                        alpha = state.crossDissolve,
                        scale = state.innerScale,
                        brightness = state.innerBrightness,
                        saturation = state.innerSaturation,
                    )
                }

                drawHingeShadow(state, sceneMapping)
                drawVignette(state, sceneMapping)

                if (state.blackOverlayAlpha > 0.002f) {
                    drawRect(Color.Black, alpha = state.blackOverlayAlpha.coerceIn(0f, 1f))
                }
            },
    )
}

/**
 * Draw one scene layer through the current viewport.
 *
 * The source rect is the part of the texture the viewport currently covers, which is what
 * makes content expand outward from the hinge rather than simply zoom.
 */
private fun DrawScope.drawSceneLayer(
    image: androidx.compose.ui.graphics.ImageBitmap,
    sceneRect: dev.foldphase.core.SceneRect,
    state: FoldVisualState,
    alpha: Float,
    scale: Float,
    brightness: Float,
    saturation: Float,
) {
    val vp = state.viewport

    // Viewport (scene space) -> normalised position inside this texture's scene rect.
    val w = sceneRect.width.coerceAtLeast(1e-5f)
    val h = sceneRect.height.coerceAtLeast(1e-5f)
    var u0 = (vp.left - sceneRect.left) / w
    var v0 = (vp.top - sceneRect.top) / h
    var u1 = (vp.right - sceneRect.left) / w
    var v1 = (vp.bottom - sceneRect.top) / h

    // Apply the layer scale about the centre, matching scaleAboutCenter() in the shader.
    val cu = (u0 + u1) * 0.5f
    val cv = (v0 + v1) * 0.5f
    val s = scale.coerceAtLeast(1e-3f)
    u0 = cu + (u0 - cu) / s
    u1 = cu + (u1 - cu) / s
    v0 = cv + (v0 - cv) / s
    v1 = cv + (v1 - cv) / s

    val srcX = (u0 * image.width).roundToInt().coerceIn(0, image.width - 1)
    val srcY = (v0 * image.height).roundToInt().coerceIn(0, image.height - 1)
    val srcW = ((u1 - u0) * image.width).roundToInt().coerceIn(1, image.width - srcX)
    val srcH = ((v1 - v0) * image.height).roundToInt().coerceIn(1, image.height - srcY)

    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = gradeFilter(brightness, saturation),
    )
}

/** Brightness + saturation as a single ColorMatrix, mirroring the shader's applyGrade. */
private fun gradeFilter(brightness: Float, saturation: Float): ColorFilter? {
    if (brightness > 0.999f && saturation > 0.999f) return null
    val m = ColorMatrix().apply {
        setToSaturation(saturation.coerceIn(0f, 1f))
    }
    val b = brightness.coerceIn(0f, 1f)
    // Scale the RGB rows; leave alpha alone.
    val v = m.values
    for (row in 0..2) {
        for (col in 0..3) {
            v[row * 5 + col] *= b
        }
    }
    return ColorFilter.colorMatrix(m)
}

/** A soft dark band hugging the fold line, anchored where the hinge actually is. */
private fun DrawScope.drawHingeShadow(state: FoldVisualState, mapping: SceneMapping) {
    val intensity = state.hingeShadowIntensity
    if (intensity <= 0.002f) return

    val vp = state.viewport
    // Where the fold line sits inside the current viewport, as a screen fraction.
    val span = vp.width.coerceAtLeast(1e-5f)
    val hingeFraction = Curves.clamp((mapping.hingePosition - vp.left) / span)
    val centerX = hingeFraction * size.width
    val halfWidth = size.width * 0.22f

    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to Color.Black.copy(alpha = intensity * 0.85f),
                1f to Color.Transparent,
            ),
            startX = centerX - halfWidth,
            endX = centerX + halfWidth,
        ),
        topLeft = Offset(centerX - halfWidth, 0f),
        size = Size(halfWidth * 2f, size.height),
    )
}

/** Hinge-centred vignette, matching the shader's placement rather than the screen centre. */
private fun DrawScope.drawVignette(state: FoldVisualState, mapping: SceneMapping) {
    val intensity = state.vignetteIntensity
    if (intensity <= 0.002f) return

    val vp = state.viewport
    val span = vp.width.coerceAtLeast(1e-5f)
    val hingeFraction = Curves.clamp((mapping.hingePosition - vp.left) / span)

    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.35f to Color.Transparent,
                1f to Color.Black.copy(alpha = intensity),
            ),
            center = Offset(hingeFraction * size.width, size.height * 0.5f),
            radius = maxOf(size.width, size.height) * 0.75f,
        ),
    )
}
