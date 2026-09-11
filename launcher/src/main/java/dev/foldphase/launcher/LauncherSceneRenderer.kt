package dev.foldphase.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import dev.foldphase.core.Curves
import dev.foldphase.engine.FoldVisualState

/**
 * Renders a [HomeScene] into a single scene-space surface, interpolating between the
 * cover and inner grid layouts by fold progress.
 *
 * ## The continuity trick
 *
 * Rather than rendering "the cover layout" and then "the inner layout" and crossfading
 * them, this renders **one layout whose geometry is interpolated**. Icon #7's position is
 * `lerp(coverPlacement, innerPlacement, t)`, so it travels continuously from where it sat
 * on the cover display to where it sits on the inner display. There is no moment at which
 * two copies of icon #7 exist, which is what a crossfade would give you and what makes a
 * crossfade look like a crossfade.
 *
 * This is the payoff for holding the scene semantically (see [HomeScene]) and it is the
 * one thing a screenshot-based implementation structurally cannot do.
 *
 * Drawing is done with primitives on a [DrawScope] rather than with composables per icon
 * because an icon grid re-laid-out every frame at 120 Hz must not allocate composition
 * nodes. All state reads happen in the draw lambda so only the draw phase re-runs
 * (brief §22).
 */
@Composable
fun LauncherSceneRenderer(
    scene: HomeScene,
    visualState: State<FoldVisualState>,
    modifier: Modifier = Modifier,
    coverSpec: GridSpec = GridSpec.COVER,
    innerSpec: GridSpec = GridSpec.INNER,
) {
    // Layouts depend only on the scene and the specs, never on progress, so they are
    // computed once and reused for every frame of the transition.
    val coverLayout = remember(scene, coverSpec) {
        HomeSceneLayout.layoutPage(scene, coverSpec) to HomeSceneLayout.layoutDock(scene, coverSpec)
    }
    val innerLayout = remember(scene, innerSpec) {
        HomeSceneLayout.layoutPage(scene, innerSpec) to HomeSceneLayout.layoutDock(scene, innerSpec)
    }

    val interpolator = remember(coverLayout, innerLayout) {
        LauncherSceneInterpolator(
            coverItems = coverLayout.first,
            innerItems = innerLayout.first,
            coverDock = coverLayout.second,
            innerDock = innerLayout.second,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val state = visualState.value
                drawScene(scene, state, interpolator)
            },
    )
}

private fun DrawScope.drawScene(
    scene: HomeScene,
    state: FoldVisualState,
    interpolator: LauncherSceneInterpolator,
) {
    // The geometry blend is driven by how far the viewport has travelled, not by raw
    // progress, so icon motion stays locked to the scene expansion the shader is doing.
    val vp = state.viewport
    val t = Curves.clamp(
        if (interpolator.viewportSpan > 1e-4f) {
            (vp.width - interpolator.coverViewportWidth) / interpolator.viewportSpan
        } else {
            state.progress
        },
    )

    drawWallpaper(scene, state)

    val iconAlpha = state.iconAlpha.coerceIn(0f, 1f)
    if (iconAlpha <= 0.002f) return

    interpolator.forEachItem(t) { placement, blendedAlpha ->
        drawItem(placement, iconAlpha * blendedAlpha, state.iconScale)
    }
    interpolator.forEachDockItem(t) { placement, blendedAlpha ->
        drawItem(placement, iconAlpha * blendedAlpha, state.iconScale)
    }
}

private fun DrawScope.drawWallpaper(scene: HomeScene, state: FoldVisualState) {
    // The wallpaper scales slightly more than the icons do. That differential is the
    // parallax cue that makes the scene read as having depth rather than being a flat
    // image being zoomed (brief §9: "wallpaper begins moving perceptually backward").
    val s = state.wallpaperScale
    val w = size.width * s
    val h = size.height * s
    val dx = (size.width - w) * 0.5f
    val dy = (size.height - h) * 0.5f

    translate(dx, dy) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(scene.wallpaper.gradientTop),
                    Color(scene.wallpaper.gradientBottom),
                ),
                startY = 0f,
                endY = h,
            ),
            size = Size(w, h),
            alpha = state.backgroundAlpha.coerceIn(0f, 1f),
        )
    }
}

private fun DrawScope.drawItem(placement: ItemPlacement, alpha: Float, scale: Float) {
    if (alpha <= 0.002f) return

    val cx = placement.centerX * size.width
    val cy = placement.centerY * size.height
    // Square icons: use the shorter cell dimension so a wide cell does not stretch them.
    val cell = minOf(placement.width * size.width, placement.height * size.height)
    val iconSize = cell * 0.62f * scale

    val color = Color(placement.item.tint)
    val corner = iconSize * 0.26f

    drawRoundRectCompat(
        color = color.copy(alpha = alpha),
        left = cx - iconSize * 0.5f,
        top = cy - iconSize * 0.5f,
        width = iconSize,
        height = iconSize,
        corner = corner,
    )
}

private fun DrawScope.drawRoundRectCompat(
    color: Color,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        style = Fill,
    )
}

/**
 * Pairs cover and inner placements by item id and interpolates between them.
 *
 * Matching by **id** rather than by index is the load-bearing detail. An item present on
 * one layout but not the other (a widget that does not fit the narrow cover grid, say)
 * must fade rather than teleport into an unrelated item's slot, and only an id match can
 * tell the difference.
 */
internal class LauncherSceneInterpolator(
    coverItems: List<ItemPlacement>,
    innerItems: List<ItemPlacement>,
    coverDock: List<ItemPlacement>,
    innerDock: List<ItemPlacement>,
) {
    private val itemPairs = pair(coverItems, innerItems)
    private val dockPairs = pair(coverDock, innerDock)

    /** Cached so the renderer can express blend as a function of viewport width. */
    var coverViewportWidth: Float = 0f
    var viewportSpan: Float = 1f

    private fun pair(
        cover: List<ItemPlacement>,
        inner: List<ItemPlacement>,
    ): List<Pair<ItemPlacement?, ItemPlacement?>> {
        val byIdCover = cover.associateBy { it.item.id }
        val byIdInner = inner.associateBy { it.item.id }
        val ids = LinkedHashSet<String>().apply {
            addAll(cover.map { it.item.id })
            addAll(inner.map { it.item.id })
        }
        return ids.map { id -> byIdCover[id] to byIdInner[id] }
    }

    inline fun forEachItem(t: Float, body: (ItemPlacement, Float) -> Unit) {
        emit(itemPairs, t, body)
    }

    inline fun forEachDockItem(t: Float, body: (ItemPlacement, Float) -> Unit) {
        emit(dockPairs, t, body)
    }

    @PublishedApi
    internal inline fun emit(
        pairs: List<Pair<ItemPlacement?, ItemPlacement?>>,
        t: Float,
        body: (ItemPlacement, Float) -> Unit,
    ) {
        for ((cover, inner) in pairs) {
            when {
                cover != null && inner != null -> body(lerpPlacement(cover, inner, t), 1f)
                // Present on only one side: hold its own position and fade it, so it
                // vanishes gracefully instead of sliding to an unrelated slot.
                cover != null -> body(cover, 1f - t)
                inner != null -> body(inner, t)
            }
        }
    }

    @PublishedApi
    internal fun lerpPlacement(a: ItemPlacement, b: ItemPlacement, t: Float): ItemPlacement =
        ItemPlacement(
            item = a.item,
            centerX = Curves.lerp(a.centerX, b.centerX, t),
            centerY = Curves.lerp(a.centerY, b.centerY, t),
            width = Curves.lerp(a.width, b.width, t),
            height = Curves.lerp(a.height, b.height, t),
        )
}

/** Handy for tests and previews: a deterministic scene with no external dependencies. */
fun sampleHomeScene(): HomeScene {
    val palette = listOf(
        0xFF5B8DEF, 0xFF9B6BEF, 0xFFEF6B8D, 0xFFEFA96B,
        0xFF6BEFA9, 0xFF6BD5EF, 0xFFEFD56B, 0xFF8D8DEF,
    ).map { it.toInt() }

    val items = (0 until 18).map { i ->
        HomeItem(
            id = "app_$i",
            label = "App $i",
            column = i % 4,
            row = i / 4,
            tint = palette[i % palette.size],
        )
    }
    val dock = (0 until 4).map { i ->
        HomeItem(
            id = "dock_$i",
            label = "Dock $i",
            column = i,
            tint = palette[(i + 3) % palette.size],
        )
    }
    return HomeScene(pages = listOf(HomePage(items)), dock = dock)
}

private fun Color.toArgbInt(): Int = toArgb()
