package dev.sumi.terminal.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Sumi {
    // 6. Diretrizes de Estetica Wabi-Sabi (Japonesa)
    val Sumi1 = Color(0xFF121212)      // Tinta preta pura lavada. Fundo primario.
    val Sumi2 = Color(0xFF181818)      // Carvao elevado para docks, dialogos e headers.
    val Sumi3 = Color(0xFF222222)      // Superficie terciaria sutil.
    val Shironeri = Color(0xFFE6E6E6)  // Seda crua branqueada. Texto principal.
    val Paper = Color(0xFFF2EFE9)      // Papel Washi para titulos e abas ativas.
    val Kinari = Color(0xFFE6C98A)     // Algodao cru nao branqueado. Destaque de ancoras, cursor e confirmacoes.
    val Kurenai = Color(0xFFD9383A)    // Carmesim imperial. Apenas erros de kernel/bash e alertas criticos.
    val Aizome = Color(0xFF204051)     // Azul indigo tradicional. Selecoes e diretorios ativos.
    val Matcha = Color(0xFF7BA17A)     // Verde cha cerimonial para status saudavel.
    val Muted = Color(0xFF8C8881)      // Cinza pedra para metadados e linhas inativas.
    val Hairline = Color(0xFF2A2927)   // Divisoria ultra-fina de 1px.
    val HairlineWidth = 1.dp

    val Mono = SumiFonts.Mono
    val Sans = SumiFonts.Sans

    fun <T> softSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

val LocalWabiSabiPalette = staticCompositionLocalOf { SumiPalettes.Carvao }

@Composable
fun SumiTheme(
    palette: WabiSabiPalette = SumiPalettes.Carvao,
    content: @Composable () -> Unit
) {
    val scheme = darkColorScheme(
        primary = palette.primaryAccent,
        onPrimary = Sumi.Sumi1,
        secondary = palette.directoryHighlight,
        onSecondary = Sumi.Paper,
        tertiary = Sumi.Aizome,
        background = palette.background,
        onBackground = palette.foreground,
        surface = palette.surface,
        onSurface = palette.foreground,
        surfaceVariant = Sumi.Sumi3,
        onSurfaceVariant = Sumi.Muted,
        error = palette.errorHighlight,
        onError = Sumi.Paper,
        outline = Sumi.Hairline
    )

    CompositionLocalProvider(LocalWabiSabiPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SumiTypography,
            content = content
        )
    }
}
