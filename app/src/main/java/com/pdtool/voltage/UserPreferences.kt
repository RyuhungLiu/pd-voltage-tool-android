package com.pdtool.voltage

import android.content.Context

enum class UsageMode { SIMPLE, PROFESSIONAL }

data class QuickPreset(
    val voltageMv: Int,
    val currentMa: Int,
    val favorite: Boolean = false,
) {
    companion object {
        val DEFAULTS = listOf(5, 9, 12, 15, 18, 20, 24, 28).map {
            QuickPreset(voltageMv = it * 1_000, currentMa = 5_000)
        }

        fun favoriteFirst(presets: List<QuickPreset>): List<Int> = presets.indices.sortedWith(
            compareByDescending<Int> { presets[it].favorite }.thenBy { it },
        )
    }
}

class UserPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun usageMode(): UsageMode {
        preferences.getString(KEY_USAGE_MODE, null)?.let { saved ->
            return runCatching { UsageMode.valueOf(saved) }.getOrDefault(UsageMode.SIMPLE)
        }
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val isUpgrade = packageInfo.lastUpdateTime - packageInfo.firstInstallTime > INSTALL_TIME_TOLERANCE_MS
        return if (isUpgrade) UsageMode.PROFESSIONAL else UsageMode.SIMPLE
    }

    fun setUsageMode(mode: UsageMode) {
        preferences.edit().putString(KEY_USAGE_MODE, mode.name).apply()
    }

    fun quickPresets(): List<QuickPreset> = QuickPreset.DEFAULTS.mapIndexed { index, fallback ->
        QuickPreset(
            voltageMv = preferences.getInt("quick_${index}_voltage_mv", fallback.voltageMv),
            currentMa = preferences.getInt("quick_${index}_current_ma", fallback.currentMa),
            favorite = preferences.getBoolean("quick_${index}_favorite", fallback.favorite),
        )
    }

    fun saveQuickPreset(index: Int, preset: QuickPreset) {
        require(index in QuickPreset.DEFAULTS.indices)
        preferences.edit()
            .putInt("quick_${index}_voltage_mv", preset.voltageMv)
            .putInt("quick_${index}_current_ma", preset.currentMa)
            .putBoolean("quick_${index}_favorite", preset.favorite)
            .apply()
    }

    fun resetQuickPreset(index: Int) {
        require(index in QuickPreset.DEFAULTS.indices)
        preferences.edit()
            .remove("quick_${index}_voltage_mv")
            .remove("quick_${index}_current_ma")
            .remove("quick_${index}_favorite")
            .apply()
    }

    fun lastVoltage(): String = preferences.getString(KEY_LAST_VOLTAGE, "5.00") ?: "5.00"
    fun lastCurrent(): String = preferences.getString(KEY_LAST_CURRENT, "5.0") ?: "5.0"

    fun saveLastPreset(voltage: String, current: String) {
        preferences.edit().putString(KEY_LAST_VOLTAGE, voltage).putString(KEY_LAST_CURRENT, current).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "user_preferences"
        private const val KEY_USAGE_MODE = "usage_mode"
        private const val KEY_LAST_VOLTAGE = "last_voltage"
        private const val KEY_LAST_CURRENT = "last_current"
        private const val INSTALL_TIME_TOLERANCE_MS = 2_000L
    }
}
