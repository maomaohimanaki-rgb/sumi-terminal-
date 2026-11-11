package dev.sumi.terminal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "terminal_prefs")

enum class CursorStyle(val label: String) {
    BLOCK("Bloco ▊"),
    BEAM("Linha │"),
    UNDERLINE("Sublinhado ▁")
}

data class TerminalSettings(
    val paletteName: String = "CARVAO",
    val customBackgroundPath: String? = "SUMI_ART",
    val backgroundBrightness: Float = 0.45f,
    val autoCommandColors: Boolean = true,
    val fontSizeSp: Float = 13.0f,
    val keepScreenOn: Boolean = false,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val hapticFeedback: Boolean = true
)

class TerminalPreferences(private val context: Context) {
    private object Keys {
        val PALETTE = stringPreferencesKey("palette")
        val BG_PATH = stringPreferencesKey("bg_path")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val AUTO_COLORS = booleanPreferencesKey("auto_colors")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val HAPTIC = booleanPreferencesKey("haptic_feedback")
    }

    val settings: Flow<TerminalSettings> = context.dataStore.data.map { prefs ->
        val styleRaw = prefs[Keys.CURSOR_STYLE] ?: CursorStyle.BLOCK.name
        val style = runCatching { CursorStyle.valueOf(styleRaw) }.getOrDefault(CursorStyle.BLOCK)
        TerminalSettings(
            paletteName = prefs[Keys.PALETTE] ?: "CARVAO",
            customBackgroundPath = if (prefs.contains(Keys.BG_PATH)) prefs[Keys.BG_PATH] else "SUMI_ART",
            backgroundBrightness = (prefs[Keys.BRIGHTNESS] ?: 0.45f).coerceIn(0.15f, 1.5f),
            autoCommandColors = prefs[Keys.AUTO_COLORS] ?: true,
            fontSizeSp = (prefs[Keys.FONT_SIZE] ?: 13.0f).coerceIn(9.0f, 24.0f),
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            cursorStyle = style,
            hapticFeedback = prefs[Keys.HAPTIC] ?: true
        )
    }

    suspend fun setPalette(name: String) = context.dataStore.edit { it[Keys.PALETTE] = name }

    suspend fun setCustomBackground(path: String?) = context.dataStore.edit {
        if (path != null) it[Keys.BG_PATH] = path else it[Keys.BG_PATH] = "NONE"
    }

    suspend fun setBrightness(value: Float) = context.dataStore.edit {
        it[Keys.BRIGHTNESS] = value.coerceIn(0.15f, 1.5f)
    }

    suspend fun setAutoColors(enabled: Boolean) = context.dataStore.edit {
        it[Keys.AUTO_COLORS] = enabled
    }

    suspend fun setFontSize(sizeSp: Float) = context.dataStore.edit {
        it[Keys.FONT_SIZE] = sizeSp.coerceIn(9.0f, 24.0f)
    }

    suspend fun setKeepScreenOn(enabled: Boolean) = context.dataStore.edit {
        it[Keys.KEEP_SCREEN_ON] = enabled
    }

    suspend fun setCursorStyle(style: CursorStyle) = context.dataStore.edit {
        it[Keys.CURSOR_STYLE] = style.name
    }

    suspend fun setHapticFeedback(enabled: Boolean) = context.dataStore.edit {
        it[Keys.HAPTIC] = enabled
    }
}
