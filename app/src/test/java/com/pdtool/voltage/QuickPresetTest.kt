package com.pdtool.voltage

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickPresetTest {
    @Test
    fun defaultsMatchCommonVoltageListAtFiveAmps() {
        assertEquals(listOf(5, 9, 12, 15, 18, 20, 24, 28), QuickPreset.DEFAULTS.map { it.voltageMv / 1_000 })
        assertEquals(List(8) { 5_000 }, QuickPreset.DEFAULTS.map { it.currentMa })
    }

    @Test
    fun favoritesSortFirstAndKeepStableSlotOrder() {
        val presets = QuickPreset.DEFAULTS.mapIndexed { index, preset ->
            preset.copy(favorite = index == 5 || index == 2)
        }
        assertEquals(listOf(2, 5, 0, 1, 3, 4, 6, 7), QuickPreset.favoriteFirst(presets))
    }
}
