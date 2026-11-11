package dev.sumi.terminal.ui.terminal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sumi.terminal.data.preferences.CursorStyle
import dev.sumi.terminal.data.preferences.TerminalSettings
import dev.sumi.terminal.ui.components.ColorPalettePicker
import dev.sumi.terminal.ui.theme.Sumi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@Composable
fun AppearancePanel(
    settings: TerminalSettings,
    onSelectPalette: (String) -> Unit,
    onSetBackgroundPath: (String?) -> Unit,
    onSetBrightness: (Float) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onSetCursorStyle: (CursorStyle) -> Unit,
    onSetAutoColors: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val destFile = File(context.filesDir, "sumi_wallpaper.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                onSetBackgroundPath(destFile.absolutePath)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Sumi.Sumi2)
                .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(16.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
                .testTag("appearance_panel_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Estética Wabi-Sabi • Aparência",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "✕",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp)
                )
            }

            // Font size slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = String.format(Locale.US, "Tamanho da Fonte: %.1f sp", settings.fontSizeSp),
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp
                )
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = onSetFontSize,
                    valueRange = 9f..22f,
                    colors = SliderDefaults.colors(
                        thumbColor = Sumi.Kinari,
                        activeTrackColor = Sumi.Kinari
                    )
                )
            }

            // Cursor style selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Estilo do Cursor",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CursorStyle.entries.forEach { style ->
                        val active = settings.cursorStyle == style
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Sumi.Kinari else Sumi.Sumi1)
                                .border(
                                    Sumi.HairlineWidth,
                                    if (active) Sumi.Kinari else Sumi.Hairline,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSetCursorStyle(style) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = style.label,
                                color = if (active) Sumi.Sumi1 else Sumi.Shironeri,
                                fontFamily = Sumi.Mono,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Wallpaper controls
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Papel de Parede do Terminal",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Sumi.Sumi3)
                            .border(Sumi.HairlineWidth, Sumi.Kinari.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Selecionar Imagem",
                            color = Sumi.Kinari,
                            fontFamily = Sumi.Mono,
                            fontSize = 11.sp
                        )
                    }
                    if (settings.customBackgroundPath != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Sumi.Sumi3)
                                .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(8.dp))
                                .clickable { onSetBackgroundPath(null) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Remover",
                                color = Sumi.Kurenai,
                                fontFamily = Sumi.Mono,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                if (settings.customBackgroundPath != null) {
                    Slider(
                        value = settings.backgroundBrightness,
                        onValueChange = onSetBrightness,
                        valueRange = 0.05f..0.85f,
                        colors = SliderDefaults.colors(
                            thumbColor = Sumi.Kinari,
                            activeTrackColor = Sumi.Kinari
                        )
                    )
                }
            }

            // Switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Coloração Semântica de Comandos",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Sans,
                    fontSize = 13.sp
                )
                Switch(
                    checked = settings.autoCommandColors,
                    onCheckedChange = onSetAutoColors,
                    colors = SwitchDefaults.colors(checkedThumbColor = Sumi.Kinari)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Manter Tela Sempre Ligada",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Sans,
                    fontSize = 13.sp
                )
                Switch(
                    checked = settings.keepScreenOn,
                    onCheckedChange = onSetKeepScreenOn,
                    colors = SwitchDefaults.colors(checkedThumbColor = Sumi.Kinari)
                )
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = "Paletas Wabi-Sabi (6 Temas)",
                color = Sumi.Kinari,
                fontFamily = Sumi.Mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )

            ColorPalettePicker(
                selectedPaletteId = settings.paletteName,
                onSelectPalette = { onSelectPalette(it.id) }
            )
        }
    }
}
