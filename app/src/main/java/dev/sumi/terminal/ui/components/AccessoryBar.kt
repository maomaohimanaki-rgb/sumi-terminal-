package dev.sumi.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import dev.sumi.terminal.terminal.Mods
import dev.sumi.terminal.terminal.TermKey
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi

@Composable
fun AccessoryBar(
    mods: Mods,
    hasSelection: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onKeyPress: (TermKey) -> Unit,
    onSymbolPress: (String) -> Unit,
    onCopySelection: () -> Unit,
    onPasteFromClipboard: () -> Unit
) {
    val palette = LocalWabiSabiPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("accessory_key_bar"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccessoryKeyButton(
                label = "ESC",
                onClick = { onKeyPress(TermKey.ESC) }
            )
            AccessoryKeyButton(
                label = "TAB",
                onClick = { onKeyPress(TermKey.TAB) }
            )
            AccessoryKeyButton(
                label = "CTRL",
                isActive = mods.ctrl,
                onClick = onToggleCtrl
            )
            AccessoryKeyButton(
                label = "ALT",
                isActive = mods.alt,
                onClick = onToggleAlt
            )
            AccessoryKeyButton(
                label = "SHIFT",
                isActive = mods.shift,
                onClick = onToggleShift
            )
            AccessoryKeyButton(
                label = "↑",
                onClick = { onKeyPress(TermKey.UP) }
            )
            AccessoryKeyButton(
                label = "↓",
                onClick = { onKeyPress(TermKey.DOWN) }
            )
            AccessoryKeyButton(
                label = "←",
                onClick = { onKeyPress(TermKey.LEFT) }
            )
            AccessoryKeyButton(
                label = "→",
                onClick = { onKeyPress(TermKey.RIGHT) }
            )
            if (hasSelection) {
                AccessoryKeyButton(
                    label = "COPIAR",
                    isAccent = true,
                    onClick = onCopySelection
                )
            }
            AccessoryKeyButton(
                label = "COLAR",
                isAccent = true,
                onClick = onPasteFromClipboard
            )
            AccessoryKeyButton(
                label = "^C",
                onClick = { onKeyPress(TermKey.CTRL_C) }
            )
            AccessoryKeyButton(
                label = "^D",
                onClick = { onKeyPress(TermKey.CTRL_D) }
            )
            AccessoryKeyButton(
                label = "^L",
                onClick = { onKeyPress(TermKey.CTRL_L) }
            )
            listOf("-", "/", "|", "~", "$", "*", "&", ";", "_").forEach { sym ->
                AccessoryKeyButton(
                    label = sym,
                    onClick = { onSymbolPress(sym) }
                )
            }
            AccessoryKeyButton(
                label = "HOME",
                onClick = { onKeyPress(TermKey.HOME) }
            )
            AccessoryKeyButton(
                label = "END",
                onClick = { onKeyPress(TermKey.END) }
            )
            AccessoryKeyButton(
                label = "PGUP",
                onClick = { onKeyPress(TermKey.PGUP) }
            )
            AccessoryKeyButton(
                label = "PGDN",
                onClick = { onKeyPress(TermKey.PGDN) }
            )
        }
    }
}

@Composable
private fun AccessoryKeyButton(
    label: String,
    isActive: Boolean = false,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalWabiSabiPalette.current
    val bg = when {
        isActive -> Sumi.Kinari
        isAccent -> palette.primaryAccent.copy(alpha = 0.16f)
        else -> palette.background
    }
    val fg = when {
        isActive -> Sumi.Sumi1
        isAccent -> palette.primaryAccent
        else -> Sumi.Shironeri
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(
                width = Sumi.HairlineWidth,
                color = if (isActive) Sumi.Kinari else if (isAccent) palette.primaryAccent.copy(alpha = 0.6f) else Sumi.Hairline,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .testTag("acc_key_$label"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = Sumi.Mono,
            fontWeight = if (isActive || isAccent) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}
