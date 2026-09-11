package dev.foldphase.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSceneLayoutTest {

    private val scene = sampleHomeScene()

    @Test
    fun `every placement lands inside the scene`() {
        listOf(GridSpec.COVER, GridSpec.INNER).forEach { spec ->
            (HomeSceneLayout.layoutPage(scene, spec) + HomeSceneLayout.layoutDock(scene, spec))
                .forEach { p ->
                    assertTrue("${p.item.id} centerX ${p.centerX}", p.centerX in 0f..1f)
                    assertTrue("${p.item.id} centerY ${p.centerY}", p.centerY in 0f..1f)
                    assertTrue("${p.item.id} width ${p.width}", p.width > 0f)
                    assertTrue("${p.item.id} height ${p.height}", p.height > 0f)
                }
        }
    }

    /**
     * A grid too small for every item must drop the overflow rather than lay it out
     * off-screen. 2x2 holds four of the sample scene's eighteen items.
     */
    @Test
    fun `items that overflow the grid are dropped not clamped`() {
        val narrow = GridSpec(columns = 2, rows = 2)
        val placements = HomeSceneLayout.layoutPage(scene, narrow)
        assertEquals(4, placements.size)
        placements.forEach {
            assertTrue(it.centerX in 0f..1f && it.centerY in 0f..1f)
        }
    }

    /** A widget that does not fit is dropped, and does not displace reflowed items. */
    @Test
    fun `widgets keep explicit placement and drop when they do not fit`() {
        val widget = HomeItem(
            id = "w1",
            label = "Widget",
            kind = ItemKind.WIDGET,
            column = 4,
            row = 0,
            columnSpan = 2,
        )
        val withWidget = HomeScene(pages = listOf(HomePage(listOf(widget) + scene.pages[0].items)))

        // Fits the 6-column inner grid.
        assertTrue(
            HomeSceneLayout.layoutPage(withWidget, GridSpec.INNER).any { it.item.id == "w1" },
        )
        // Does not fit the 4-column cover grid.
        assertTrue(
            HomeSceneLayout.layoutPage(withWidget, GridSpec.COVER).none { it.item.id == "w1" },
        )
    }

    @Test
    fun `dock spreads across the full width`() {
        val dock = HomeSceneLayout.layoutDock(scene, GridSpec.COVER)
        assertEquals(4, dock.size)
        assertTrue(dock.zipWithNext().all { (a, b) -> b.centerX > a.centerX })
    }

    /**
     * The load-bearing property of launcher mode: an item present in both layouts is the
     * *same* item in both, so the transition can move it rather than crossfading two
     * unrelated pictures of it.
     */
    @Test
    fun `same item ids appear in both grids so they can be interpolated`() {
        val coverIds = HomeSceneLayout.layoutPage(scene, GridSpec.COVER).map { it.item.id }.toSet()
        val innerIds = HomeSceneLayout.layoutPage(scene, GridSpec.INNER).map { it.item.id }.toSet()
        val shared = coverIds intersect innerIds
        assertTrue("only $shared shared between grids", shared.size >= 8)
    }

    /**
     * Going from 4 columns to 6 means every cover column maps onto a wider stride, so
     * icons must visibly *spread apart* during the unfold rather than only growing. That
     * spread is the motion the reference animation's "flows across displays" describes.
     */
    @Test
    fun `inner grid spreads items further apart than the cover grid`() {
        val cover = HomeSceneLayout.layoutPage(scene, GridSpec.COVER).associateBy { it.item.id }
        val inner = HomeSceneLayout.layoutPage(scene, GridSpec.INNER).associateBy { it.item.id }

        val coverSpread = cover.values.maxOf { it.centerX } - cover.values.minOf { it.centerX }
        val innerSpread = inner.values.maxOf { it.centerX } - inner.values.minOf { it.centerX }
        assertTrue("cover $coverSpread vs inner $innerSpread", innerSpread > coverSpread)
    }
}
