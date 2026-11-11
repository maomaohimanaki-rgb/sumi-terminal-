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

    private val defaultAnsi16 = listOf(
        Color(0xFF181818), // 0 Black
        Color(0xFFD9383A), // 1 Red (Kurenai)
        Color(0xFF7BA17A), // 2 Green (Matcha)
        Color(0xFFE6C98A), // 3 Yellow (Kinari)
        Color(0xFF4A7C9B), // 4 Blue (Aizome light)
        Color(0xFFC47A9E), // 5 Magenta (Sakura)
        Color(0xFF6BA39E), // 6 Cyan
        Color(0xFFE6E6E6), // 7 White (Shironeri)
        Color(0xFF8C8881), // 8 Bright Black (Muted)
        Color(0xFFE85A5C), // 9 Bright Red
        Color(0xFF96BD95), // 10 Bright Green
        Color(0xFFF2D9A0), // 11 Bright Yellow
        Color(0xFF689EC2), // 12 Bright Blue
        Color(0xFFD995B6), // 13 Bright Magenta
        Color(0xFF87BFB9), // 14 Bright Cyan
        Color(0xFFF2EFE9)  // 15 Bright White (Paper)
    )

    val Carvao = WabiSabiPalette(
        id = "carvao",
        displayName = "Carvão (Sumi)",
        kanji = "墨",
        description = "Preto tinta lavada com destaque em algodão cru Kinari e carmesim Kurenai",
        background = Color(0xFF121212),
        surface = Color(0xFF181818),
        foreground = Color(0xFFE6E6E6),
        primaryAccent = Color(0xFFE6C98A),
        commandHighlight = Color(0xFFE6C98A),
        directoryHighlight = Color(0xFF4A7C9B),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF204051),
        cursorColor = Color(0xFFE6C98A),
        ansiColors = defaultAnsi16
    )

    val Matcha = WabiSabiPalette(
        id = "matcha",
        displayName = "Matcha (Chá Cerimonial)",
        kanji = "茶",
        description = "Musgo cerimonial profundo com realce verde folha suave",
        background = Color(0xFF0F1410),
        surface = Color(0xFF161D17),
        foreground = Color(0xFFE4ECE3),
        primaryAccent = Color(0xFF7BA17A),
        commandHighlight = Color(0xFF96BD95),
        directoryHighlight = Color(0xFFE6C98A),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF233824),
        cursorColor = Color(0xFF7BA17A),
        ansiColors = defaultAnsi16
    )

    val Aizome = WabiSabiPalette(
        id = "aizome",
        displayName = "Aizome (Índigo)",
        kanji = "藍",
        description = "Tintura índigo tradicional japonesa com contraste mineral frio",
        background = Color(0xFF0E1318),
        surface = Color(0xFF141C24),
        foreground = Color(0xFFE4ECF2),
        primaryAccent = Color(0xFF689EC2),
        commandHighlight = Color(0xFF87BFB9),
        directoryHighlight = Color(0xFFE6C98A),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF204051),
        cursorColor = Color(0xFF689EC2),
        ansiColors = defaultAnsi16
    )

    val Ambar = WabiSabiPalette(
        id = "ambar",
        displayName = "Âmbar (Kitsune)",
        kanji = "狐",
        description = "Fósforo âmbar morno de baixa fadiga ocular para sessões noturnas",
        background = Color(0xFF14110C),
        surface = Color(0xFF1C1711),
        foreground = Color(0xFFF2E6D0),
        primaryAccent = Color(0xFFE6B450),
        commandHighlight = Color(0xFFE6B450),
        directoryHighlight = Color(0xFF7BA17A),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF3A2D18),
        cursorColor = Color(0xFFE6B450),
        ansiColors = defaultAnsi16
    )

    val Sakura = WabiSabiPalette(
        id = "sakura",
        displayName = "Sakura Noturno",
        kanji = "桜",
        description = "Carvão ameixa escuro com acentos de pétala seca",
        background = Color(0xFF141013),
        surface = Color(0xFF1C161A),
        foreground = Color(0xFFF0E6EC),
        primaryAccent = Color(0xFFD995B6),
        commandHighlight = Color(0xFFD995B6),
        directoryHighlight = Color(0xFFE6C98A),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF38222E),
        cursorColor = Color(0xFFD995B6),
        ansiColors = defaultAnsi16
    )

    val Monocromatico = WabiSabiPalette(
        id = "monocromatico",
        displayName = "Monocromático (Zen)",
        kanji = "禅",
        description = "Escala pura de grafite e papel Washi sem distrações cromáticas",
        background = Color(0xFF101010),
        surface = Color(0xFF171717),
        foreground = Color(0xFFE8E6E1),
        primaryAccent = Color(0xFFF2EFE9),
        commandHighlight = Color(0xFFF2EFE9),
        directoryHighlight = Color(0xFFB8B4AC),
        errorHighlight = Color(0xFFD9383A),
        selectionBackground = Color(0xFF2E2D2A),
        cursorColor = Color(0xFFF2EFE9),
        ansiColors = defaultAnsi16
    )

    val all: List<WabiSabiPalette> = listOf(
        Carvao,
        Matcha,
        Aizome,
        Ambar,
        Sakura,
        Monocromatico
    )

    fun byId(id: String): WabiSabiPalette {
        return all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Carvao
    }
}
