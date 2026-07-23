package com.pdtool.voltage

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLocale {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun currentTag(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, "")
            .orEmpty()

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, tag)
            .apply()
    }

    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        if (tag.isEmpty()) {
            val systemLocale = base.resources.configuration.locales[0]
            Locale.setDefault(systemLocale)
            return base
        }

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }
}
