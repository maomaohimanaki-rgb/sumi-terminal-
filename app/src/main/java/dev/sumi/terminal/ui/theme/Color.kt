package dev.sumi.terminal.ui.theme

import androidx.compose.ui.graphics.Color

data class WabiSabiPalette(
    val id: String,
    val displayName: String,
    val kanji: String,
    val description: String,
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val primaryAccent: Color,
    val commandHighlight: Color,
    val directoryHighlight: Color,
    val errorHighlight: Color,
    val selectionBackground: Color,
    val cursorColor: Color,
    val ansiColors: List<Color>
)

object SumiPalettes {
    val Carvao = WabiSabiPalette(
        id = "CARVAO",
        displayName = "Carvão Sumi",
        kanji = "墨",
        description = "Tinta preta pura lavada e algodão cru Kinari",
        background = Color(0xFF121212),
        surface = Color(0xFF181818),
        foreground = Color(0xFFE6E6E6),
        primaryAccent = Color(0xFFE6C98A),
        commandHighlight = Color(0xFFE6C98A),
        directoryHighlight = Color(0xFF5C8D89),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF204051),
        cursorColor = Color(0xFFE6C98A),
        ansiColors = listOf(
            Color(0xFF181818), // 0 Black
            Color(0xFFD9383A), // 1 Red (Kurenai)
            Color(0xFF7BA17A), // 2 Green (Matcha)
            Color(0xFFE6C98A), // 3 Yellow (Kinari)
            Color(0xFF537D96), // 4 Blue (Aizome)
            Color(0xFFB57C8E), // 5 Magenta (Ume)
            Color(0xFF6B9E9B), // 6 Cyan (Seiji)
            Color(0xFFD8D5CE), // 7 White (Washi)
            Color(0xFF5A5854), // 8 Bright Black
            Color(0xFFEC5B5D), // 9 Bright Red
            Color(0xFF94BD93), // 10 Bright Green
            Color(0xFFF2DBA5), // 11 Bright Yellow
            Color(0xFF739FB8), // 12 Bright Blue
            Color(0xFFD198AA), // 13 Bright Magenta
            Color(0xFF88BDB9), // 14 Bright Cyan
            Color(0xFFF5F3EF)  // 15 Bright White
        )
    )

    val Sakura = WabiSabiPalette(
        id = "SAKURA",
        displayName = "Sakura Yoru",
        kanji = "桜",
        description = "Pétalas de cerejeira sob céu noturno de Kyoto",
        background = Color(0xFF141114),
        surface = Color(0xFF1C171C),
        foreground = Color(0xFFEDE4E7),
        primaryAccent = Color(0xFFE8A5B8),
        commandHighlight = Color(0xFFE8A5B8),
        directoryHighlight = Color(0xFFC58CA1),
        errorHighlight = Color(0xFFE0475B),
        selectionBackground = Color(0xFF3D2632),
        cursorColor = Color(0xFFE8A5B8),
        ansiColors = Carvao.ansiColors.toMutableList().apply {
            this[3] = Color(0xFFE8A5B8)
            this[5] = Color(0xFFF2BAC9)
        }
    )

    val Aizome = WabiSabiPalette(
        id = "AIZOME",
        displayName = "Oceano Aizome",
        kanji = "藍",
        description = "Índigo tradicional japonês profundo e sereno",
        background = Color(0xFF0E1418),
        surface = Color(0xFF141D24),
        foreground = Color(0xFFE2EBF0),
        primaryAccent = Color(0xFF72A7C4),
        commandHighlight = Color(0xFF72A7C4),
        directoryHighlight = Color(0xFF88BDB9),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF204051),
        cursorColor = Color(0xFF72A7C4),
        ansiColors = Carvao.ansiColors.toMutableList().apply {
            this[4] = Color(0xFF72A7C4)
            this[6] = Color(0xFF88BDB9)
        }
    )

    val Ambar = WabiSabiPalette(
        id = "AMBAR",
        displayName = "Âmbar Kitsune",
        kanji = "琥",
        description = "Fósforo dourado quente de lanternas de papel",
        background = Color(0xFF14110C),
        surface = Color(0xFF1C1812),
        foreground = Color(0xFFEDE5D8),
        primaryAccent = Color(0xFFE5A952),
        commandHighlight = Color(0xFFE5A952),
        directoryHighlight = Color(0xFFD48845),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF422F18),
        cursorColor = Color(0xFFE5A952),
        ansiColors = Carvao.ansiColors.toMutableList().apply {
            this[3] = Color(0xFFE5A952)
            this[11] = Color(0xFFF5C16C)
        }
    )

    val Matcha = WabiSabiPalette(
        id = "MATCHA",
        displayName = "Matcha Chashitsu",
        kanji = "茶",
        description = "Verde chá cerimonial de Uji sobre pedra escura",
        background = Color(0xFF0F1410),
        surface = Color(0xFF161D17),
        foreground = Color(0xFFE3ECE4),
        primaryAccent = Color(0xFF8FB88B),
        commandHighlight = Color(0xFF8FB88B),
        directoryHighlight = Color(0xFF6B9E9B),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF223824),
        cursorColor = Color(0xFF8FB88B),
        ansiColors = Carvao.ansiColors.toMutableList().apply {
            this[2] = Color(0xFF8FB88B)
            this[10] = Color(0xFFA7CF9F)
        }
    )

    val Monocromatico = WabiSabiPalette(
        id = "MONOCROMATICO",
        displayName = "Monocromático Puro",
        kanji = "無",
        description = "Contraste absoluto entre tinta nanquim e papel Washi",
        background = Color(0xFF0C0C0C),
        surface = Color(0xFF141414),
        foreground = Color(0xFFF0F0F0),
        primaryAccent = Color(0xFFFFFFFF),
        commandHighlight = Color(0xFFFFFFFF),
        directoryHighlight = Color(0xFFB8B8B8),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF333333),
        cursorColor = Color(0xFFFFFFFF),
        ansiColors = List(16) { idx ->
            when (idx) {
                0 -> Color(0xFF121212)
                1, 9 -> Color(0xFFD9383A)
                in 2..7 -> Color(0xFFD8D8D8)
                else -> Color(0xFFFFFFFF)
            }
        }
    )

    val all: List<WabiSabiPalette> = listOf(
        Carvao,
        Sakura,
        Aizome,
        Ambar,
        Matcha,
        Monocromatico
    )

    fun byId(id: String): WabiSabiPalette =
        all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Carvao
}
