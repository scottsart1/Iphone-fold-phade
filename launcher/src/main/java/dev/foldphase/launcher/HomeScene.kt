package dev.foldphase.launcher

import android.graphics.drawable.Drawable

/**
 * A semantic description of the home screen (brief §15).
 *
 * ## Why semantic rather than a screenshot
 *
 * The screenshot proof-of-concept (brief §16) is the right way to *develop* the
 * transition, but it has a ceiling: two bitmaps can only ever be crossfaded, so the icons
 * on the cover display and the icons on the inner display are unrelated pixels that
 * happen to look similar. The illusion survives a fast fold and falls apart on a slow one.
 *
 * Holding the scene semantically means the cover and inner layouts are two *renderings of
 * the same objects*. Icon #7 is the same icon in both, so the transition can genuinely
 * interpolate its position, size and opacity rather than dissolving one picture into
 * another. That is what makes the brief's "one continuous scene" possible rather than
 * merely suggested.
 *
 * Everything here is deliberately plain data: no Compose types, no Android UI types
 * beyond [Drawable] for the icon itself, so the same scene can be rendered by the cover
 * renderer, the inner renderer, or rasterised to a texture for the shader.
 */
data class HomeScene(
    val wallpaper: WallpaperSpec = WallpaperSpec(),
    val pages: List<HomePage> = emptyList(),
    val dock: List<HomeItem> = emptyList(),
    /** Index of the currently visible page. Preserved across the fold (brief §15). */
    val currentPage: Int = 0,
) {
    val pageCount: Int get() = pages.size

    fun page(index: Int): HomePage? = pages.getOrNull(index)
}

/** The wallpaper, as a scene-space description rather than a bitmap. */
data class WallpaperSpec(
    /**
     * Scroll offset within the wallpaper, `[0,1]`. Preserved across the fold so the
     * wallpaper does not visibly jump when the layout changes.
     */
    val scrollX: Float = 0.5f,
    /** Optional user-supplied wallpaper. Null falls back to the generated gradient. */
    val drawable: Drawable? = null,
    /** Base colours for the generated gradient, as ARGB. */
    val gradientTop: Int = 0xFF10131A.toInt(),
    val gradientBottom: Int = 0xFF1C2230.toInt(),
)

data class HomePage(
    val items: List<HomeItem> = emptyList(),
)

/**
 * One thing on the home screen.
 *
 * ## Position is an *order*, not a coordinate
 *
 * The cover grid is 4 columns and the inner grid is 6. A single stored `(column, row)`
 * cannot describe a position in both — an item pinned to column 3 would sit against the
 * right edge of the cover grid and two columns short of the right edge of the inner one,
 * so the icons would bunch into the left two-thirds of the unfolded display instead of
 * using it.
 *
 * So ordinary items store only their **order within the page**, and each [GridSpec]
 * reflows them into its own grid — which is also what a real launcher does when its grid
 * size changes. Going from 4 to 6 columns then makes items genuinely *spread apart*
 * during the unfold rather than merely growing, which is the motion the reference
 * animation's "flows across displays" describes.
 *
 * Widgets are the exception: they occupy a specific region, so they keep explicit
 * [column]/[row] placement and are dropped from a grid they do not fit.
 */
data class HomeItem(
    val id: String,
    val label: String,
    val kind: ItemKind = ItemKind.APP,
    /** Explicit column, honoured for widgets only. Ordinary items reflow by order. */
    val column: Int = 0,
    /** Explicit row, honoured for widgets only. Ordinary items reflow by order. */
    val row: Int = 0,
    /** Cells spanned. Widgets span more than one; apps and folders are always 1x1. */
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
    val icon: Drawable? = null,
    /** ARGB fallback used when [icon] is null, so the scene always renders. */
    val tint: Int = 0xFF5B6C8F.toInt(),
    val packageName: String? = null,
    /** For folders: the items inside. */
    val children: List<HomeItem> = emptyList(),
) {
    val isWidget: Boolean get() = kind == ItemKind.WIDGET
    val isFolder: Boolean get() = kind == ItemKind.FOLDER
}

enum class ItemKind { APP, FOLDER, WIDGET, SHORTCUT }

/**
 * Grid geometry for one physical display.
 *
 * The cover and inner displays get different values, and the difference between them is
 * exactly what the transition interpolates across.
 */
data class GridSpec(
    val columns: Int,
    val rows: Int,
    /** Icon size as a fraction of the cell's shorter dimension. */
    val iconSizeFraction: Float = 0.62f,
    /** Horizontal padding as a fraction of total width. */
    val horizontalPaddingFraction: Float = 0.06f,
    val verticalPaddingFraction: Float = 0.05f,
    val showLabels: Boolean = true,
    val dockSlots: Int = 4,
    /** Height of the dock as a fraction of total height. */
    val dockHeightFraction: Float = 0.13f,
) {
    companion object {
        /**
         * Cover display: narrow, so fewer columns and no room for a generous grid.
         *
         * 4x6 with labels on. Measured against the Z Fold 7's 6.5" 21:9 cover panel,
         * which is close to an ordinary phone in aspect ratio, hence an ordinary
         * phone-like grid.
         */
        val COVER = GridSpec(
            columns = 4,
            rows = 6,
            iconSizeFraction = 0.58f,
            horizontalPaddingFraction = 0.07f,
            dockSlots = 4,
        )

        /**
         * Inner display: nearly square when open, so more columns and a roomier layout.
         *
         * 6x6 is a deliberate choice over 5x6 — going from 4 columns to 6 means every
         * cover column maps onto a 1.5-column stride, so icons visibly *spread apart*
         * during the unfold rather than merely growing. That spread is the motion the
         * reference animation's "flows across displays" description is pointing at.
         */
        val INNER = GridSpec(
            columns = 6,
            rows = 6,
            iconSizeFraction = 0.52f,
            horizontalPaddingFraction = 0.05f,
            dockSlots = 6,
        )
    }
}

/** A resolved on-screen placement, in normalised scene coordinates. */
data class ItemPlacement(
    val item: HomeItem,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Turns a [HomeScene] plus a [GridSpec] into normalised placements.
 *
 * The output is in **scene space**, so the cover placement and the inner placement of the
 * same item are directly interpolable — which is what `LauncherSceneInterpolator` does.
 */
object HomeSceneLayout {

    fun layoutPage(
        scene: HomeScene,
        spec: GridSpec,
        pageIndex: Int = scene.currentPage,
    ): List<ItemPlacement> {
        val page = scene.page(pageIndex) ?: return emptyList()

        val padX = spec.horizontalPaddingFraction
        val padY = spec.verticalPaddingFraction
        val usableW = 1f - padX * 2f
        val usableH = 1f - padY * 2f - spec.dockHeightFraction

        val cellW = usableW / spec.columns
        val cellH = usableH / spec.rows

        val out = ArrayList<ItemPlacement>(page.items.size)
        // Flow position for reflowable items, advanced independently of the list index so
        // that an explicitly-placed widget does not shift everything after it.
        var slot = 0

        page.items.forEach { item ->
            val column: Int
            val row: Int

            if (item.isWidget) {
                // Widgets occupy a region, so they keep their explicit placement and are
                // dropped from a grid too small to hold them.
                column = item.column
                row = item.row
                if (column + item.columnSpan > spec.columns) return@forEach
                if (row + item.rowSpan > spec.rows) return@forEach
            } else {
                column = slot % spec.columns
                row = slot / spec.columns
                slot++
                if (row >= spec.rows) return@forEach
            }

            val w = cellW * item.columnSpan
            val h = cellH * item.rowSpan
            out.add(
                ItemPlacement(
                    item = item,
                    centerX = padX + column * cellW + w * 0.5f,
                    centerY = padY + row * cellH + h * 0.5f,
                    width = w,
                    height = h,
                ),
            )
        }
        return out
    }

    fun layoutDock(scene: HomeScene, spec: GridSpec): List<ItemPlacement> {
        if (scene.dock.isEmpty()) return emptyList()
        val slots = maxOf(spec.dockSlots, scene.dock.size)
        val padX = spec.horizontalPaddingFraction
        val usableW = 1f - padX * 2f
        val cellW = usableW / slots
        val dockTop = 1f - spec.verticalPaddingFraction - spec.dockHeightFraction
        val cellH = spec.dockHeightFraction

        return scene.dock.take(slots).mapIndexed { i, item ->
            ItemPlacement(
                item = item,
                centerX = padX + i * cellW + cellW * 0.5f,
                centerY = dockTop + cellH * 0.5f,
                width = cellW,
                height = cellH,
            )
        }
    }
}
