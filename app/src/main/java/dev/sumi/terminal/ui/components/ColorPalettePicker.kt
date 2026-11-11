package dev.sumi.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sumi.terminal.ui.theme.Sumi
import dev.sumi.terminal.ui.theme.SumiPalettes
import dev.sumi.terminal.ui.theme.WabiSabiPalette

@Composable
fun ColorPalettePicker(
    selectedPaletteId: String,
    onSelectPalette: (WabiSabiPalette) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SumiPalettes.all.forEach { palette ->
            val isSelected = palette.id.equals(selectedPaletteId, ignoreCase = true)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.background)
                    .border(
                        width = if (isSelected) 1.5.dp else Sumi.HairlineWidth,
                        color = if (isSelected) palette.primaryAccent else Sumi.Hairline,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectPalette(palette) }
                    .padding(12.dp)
                    .testTag("palette_option_${palette.id}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.primaryAccent.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = palette.kanji,
                        color = palette.primaryAccent,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = palette.displayName,
                        color = palette.foreground,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = palette.description,
                        color = Sumi.Muted,
                        fontFamily = Sumi.Sans,
                        fontSize = 11.sp
                    )
                }

                // Swatch preview dots
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        palette.primaryAccent,
                        palette.directoryHighlight,
                        palette.errorHighlight,
                        palette.foreground
                    ).forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(swatch)
                        )
                    }
                }
            }
        }
    }
}
