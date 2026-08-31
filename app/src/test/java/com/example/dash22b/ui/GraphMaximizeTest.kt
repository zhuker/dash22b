package com.example.dash22b.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMaximizeTest {

    @Test
    fun `the grid is three rows of three, ids 2 to 10`() {
        assertEquals(listOf(listOf(2, 3, 4), listOf(5, 6, 7), listOf(8, 9, 10)), GRAPH_ROWS)
    }

    @Test
    fun `maximizing in one row leaves another row's maximized graph alone`() {
        // The reported bug: maximizing 8 (row 3) used to clear 2 (row 1).
        val afterFirst = toggleMaximized(emptyList(), 2)
        val afterSecond = toggleMaximized(afterFirst, 8)

        assertTrue(afterSecond.contains(2))
        assertTrue(afterSecond.contains(8))
        assertEquals(2, afterSecond.size)
    }

    @Test
    fun `all three rows can be maximized at once`() {
        var state = emptyList<Int>()
        listOf(3, 6, 10).forEach { state = toggleMaximized(state, it) }

        assertEquals(setOf(3, 6, 10), state.toSet())
    }

    @Test
    fun `maximizing a second graph in the same row replaces the first`() {
        val state = toggleMaximized(toggleMaximized(emptyList(), 2), 4)

        assertEquals(listOf(4), state)
    }

    @Test
    fun `replacing within a row does not disturb other rows`() {
        var state = emptyList<Int>()
        listOf(2, 6).forEach { state = toggleMaximized(state, it) }

        state = toggleMaximized(state, 3) // same row as 2

        assertEquals(setOf(3, 6), state.toSet())
    }

    @Test
    fun `clicking a maximized graph restores it`() {
        val state = toggleMaximized(toggleMaximized(emptyList(), 5), 5)

        assertTrue(state.isEmpty())
    }

    @Test
    fun `restoring one row leaves the others maximized`() {
        var state = emptyList<Int>()
        listOf(2, 5, 8).forEach { state = toggleMaximized(state, it) }

        state = toggleMaximized(state, 5)

        assertEquals(setOf(2, 8), state.toSet())
    }

    @Test
    fun `a maximized graph shows the full parameter name`() {
        assertEquals(
            "Manifold Relative Pressure",
            graphLabel("Manifold Relative Pressure", maximized = true)
        )
    }

    @Test
    fun `a tile in the grid shows the first word, which is the distinguishing one`() {
        assertEquals("Manifold", graphLabel("Manifold Relative Pressure", maximized = false))
        assertEquals("Coolant", graphLabel("Coolant Temperature", maximized = false))
        assertEquals("Fuel", graphLabel("Fuel Tank Pressure", maximized = false))
    }

    @Test
    fun `a single-word name is unchanged either way`() {
        assertEquals("Boost", graphLabel("Boost", maximized = false))
        assertEquals("Boost", graphLabel("Boost", maximized = true))
    }

    @Test
    fun `a leading space does not empty the label`() {
        // split(" ").first() on " Coolant Temp" is "", which would render a blank tile.
        assertEquals(" Coolant Temp", graphLabel(" Coolant Temp", maximized = false))
    }

    @Test
    fun `an empty name stays empty rather than throwing`() {
        assertEquals("", graphLabel("", maximized = false))
        assertEquals("", graphLabel("", maximized = true))
    }

    @Test
    fun `an id outside the grid is toggled without touching anything else`() {
        val state = toggleMaximized(listOf(2), 99)

        assertEquals(setOf(2, 99), state.toSet())
    }
}
