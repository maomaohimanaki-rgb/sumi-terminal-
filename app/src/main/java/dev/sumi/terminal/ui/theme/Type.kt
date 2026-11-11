package dev.sumi.terminal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

object SumiFonts {
    val Mono: FontFamily = try {
        FontFamily(
            Font(R.font.jetbrains_mono, FontWeight.Normal),
            Font(R.font.jetbrains_mono, FontWeight.Medium),
            Font(R.font.jetbrains_mono, FontWeight.Bold)
        )
    } catch (_: Throwable) {
        FontFamily.Monospace
    }

    val Sans: FontFamily = FontFamily.SansSerif
}

val SumiTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = SumiFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SumiFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SumiFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)
