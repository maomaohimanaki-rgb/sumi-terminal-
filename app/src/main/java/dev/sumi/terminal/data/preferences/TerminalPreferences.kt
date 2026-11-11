package dev.sumi.terminal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sumiDataStore by preferencesDataStore(name = "sumi_terminal_prefs")

enum class CursorStyle(val label: String) {
    BLOCK("Bloco (█)"),
    UNDERLINE("Sublinhado (_)"),
    BEAM("Feixe (|)")
}

data class TerminalSettings(
    val paletteName: String = "carvao",
    val customBackgroundPath: String? = null,
    val backgroundBrightness: Float = 0.25f,
    val hapticFeedback: Boolean = true,
    val fontSizeSp: Float = 13.5f,
    val autoCommandColors: Boolean = true,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val keepScreenOn: Boolean = true
)

class TerminalPreferences(private val context: Context) {

    private object Keys {
        val PALETTE = stringPreferencesKey("palette_name")
        val BG_PATH = stringPreferencesKey("custom_bg_path")
        val BG_BRIGHTNESS = floatPreferencesKey("bg_brightness")
        val HAPTIC = booleanPreferencesKey("haptic_feedback")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val AUTO_COLORS = booleanPreferencesKey("auto_command_colors")
        val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<TerminalSettings> = context.sumiDataStore.data.map { prefs ->
        val styleName = prefs[Keys.CURSOR_STYLE] ?: CursorStyle.BLOCK.name
        val style = runCatching { CursorStyle.valueOf(styleName) }.getOrDefault(CursorStyle.BLOCK)
        TerminalSettings(
            paletteName = prefs[Keys.PALETTE] ?: "carvao",
            customBackgroundPath = prefs[Keys.BG_PATH]?.takeIf { it.isNotBlank() },
            backgroundBrightness = (prefs[Keys.BG_BRIGHTNESS] ?: 0.25f).coerceIn(0.05f, 1.0f),
            hapticFeedback = prefs[Keys.HAPTIC] ?: true,
            fontSizeSp = (prefs[Keys.FONT_SIZE] ?: 13.5f).coerceIn(9f, 24f),
            autoCommandColors = prefs[Keys.AUTO_COLORS] ?: true,
            cursorStyle = style,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true
        )
    }

    suspend fun setPalette(name: String) {
        context.sumiDataStore.edit { it[Keys.PALETTE] = name }
    }

    suspend fun setCustomBackground(path: String?) {
        context.sumiDataStore.edit {
            if (path.isNullOrBlank()) it.remove(Keys.BG_PATH) else it[Keys.BG_PATH] = path
        }
    }

    suspend fun setBrightness(value: Float) {
        context.sumiDataStore.edit { it[Keys.BG_BRIGHTNESS] = value.coerceIn(0.05f, 1.0f) }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.sumiDataStore.edit { it[Keys.HAPTIC] = enabled }
    }

    suspend fun setFontSize(sizeSp: Float) {
        context.sumiDataStore.edit { it[Keys.FONT_SIZE] = sizeSp.coerceIn(9f, 24f) }
    }

    suspend fun setAutoColors(enabled: Boolean) {
        context.sumiDataStore.edit { it[Keys.AUTO_COLORS] = enabled }
    }

    suspend fun setCursorStyle(style: CursorStyle) {
        context.sumiDataStore.edit { it[Keys.CURSOR_STYLE] = style.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.sumiDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }
}
