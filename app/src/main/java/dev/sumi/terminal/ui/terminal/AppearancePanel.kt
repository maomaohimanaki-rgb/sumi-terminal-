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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sumi.terminal.data.preferences.CursorStyle
import dev.sumi.terminal.data.preferences.TerminalPreferencesSerializer
import dev.sumi.terminal.data.preferences.TerminalSettings
import dev.sumi.terminal.ui.components.ColorPalettePicker
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
    val palette = LocalWabiSabiPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val localPath = TerminalPreferencesSerializer.copyBackgroundToSandbox(context, uri)
                if (localPath != null) {
                    onSetBackgroundPath(localPath)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Sumi.Sumi2,
        contentColor = Sumi.Paper
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("appearance_panel"),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Estética Wabi-Sabi (墨)",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Persistência em tempo real via Jetpack DataStore",
                        color = Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar painel de aparência",
                        tint = Sumi.Muted
                    )
                }
            }

            // 1. Cursor Style Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ESTILO DO CURSOR RESPIRANTE",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
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
                                .background(if (active) palette.primaryAccent.copy(alpha = 0.18f) else Sumi.Sumi1)
                                .border(
                                    width = Sumi.HairlineWidth,
                                    color = if (active) palette.primaryAccent else Sumi.Hairline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSetCursorStyle(style) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = style.label,
                                color = if (active) palette.primaryAccent else Sumi.Shironeri,
                                fontFamily = Sumi.Mono,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // 2. Background Art & Photo Picker
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ATMOSFERA DE FUNDO",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isSumiArt = settings.customBackgroundPath == "SUMI_ART"
                    val isNone = settings.customBackgroundPath == "NONE" || settings.customBackgroundPath == null
                    val isCustom = !isSumiArt && !isNone

                    BackgroundOptionChip(
                        label = "Pintura Sumi-e",
                        selected = isSumiArt,
                        modifier = Modifier.weight(1f),
                        onClick = { onSetBackgroundPath("SUMI_ART") }
                    )
                    BackgroundOptionChip(
                        label = "Galeria (Foto)",
                        selected = isCustom,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    BackgroundOptionChip(
                        label = "Carvão Puro",
                        selected = isNone,
                        modifier = Modifier.weight(1f),
                        onClick = { onSetBackgroundPath("NONE") }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Brilho da Arte de Fundo", color = Sumi.Shironeri, fontFamily = Sumi.Sans, fontSize = 12.sp)
                    Text(
                        String.format(Locale.US, "%.0f%%", settings.backgroundBrightness * 100f),
                        color = palette.primaryAccent,
                        fontFamily = Sumi.Mono,
                        fontSize = 12.sp
                    )
                }
                Slider(
                    value = settings.backgroundBrightness,
                    onValueChange = onSetBrightness,
                    valueRange = 0.15f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primaryAccent,
                        activeTrackColor = palette.primaryAccent
                    )
                )
            }

            // 3. Font Size Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tamanho da Fonte (JetBrains Mono)", color = Sumi.Shironeri, fontFamily = Sumi.Sans, fontSize = 12.sp)
                    Text("${settings.fontSizeSp.toInt()} sp", color = palette.primaryAccent, fontFamily = Sumi.Mono, fontSize = 12.sp)
                }
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = onSetFontSize,
                    valueRange = 10f..22f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primaryAccent,
                        activeTrackColor = palette.primaryAccent
                    )
                )
            }

            // 4. Semantic Command Highlighting & Keep Screen On toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Coloração Semântica de Comando",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Destaca o primeiro executável (git, pacman, cargo) e erros Kurenai",
                        color = Sumi.Muted,
                        fontFamily = Sumi.Sans,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = settings.autoCommandColors,
                    onCheckedChange = onSetAutoColors,
                    colors = SwitchDefaults.colors(checkedThumbColor = palette.primaryAccent)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manter Tela Sempre Ativa",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Impede suspensão durante compilações longas no PRoot",
                        color = Sumi.Muted,
                        fontFamily = Sumi.Sans,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = settings.keepScreenOn,
                    onCheckedChange = onSetKeepScreenOn,
                    colors = SwitchDefaults.colors(checkedThumbColor = palette.primaryAccent)
                )
            }

            // 5. Wabi-Sabi Palettes
            Text(
                text = "PALETAS WABI-SABI",
                color = Sumi.Muted,
                fontFamily = Sumi.Mono,
                fontSize = 11.sp
            )
            ColorPalettePicker(
                selectedPaletteId = settings.paletteName,
                onSelectPalette = { onSelectPalette(it.id) }
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BackgroundOptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalWabiSabiPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.primaryAccent.copy(alpha = 0.18f) else Sumi.Sumi1)
            .border(
                width = Sumi.HairlineWidth,
                color = if (selected) palette.primaryAccent else Sumi.Hairline,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) palette.primaryAccent else Sumi.Shironeri,
            fontFamily = Sumi.Mono,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
    }
}
