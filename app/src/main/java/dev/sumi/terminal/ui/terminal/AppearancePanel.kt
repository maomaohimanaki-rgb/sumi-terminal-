package dev.sumi.terminal.ui.terminal

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.sumi.terminal.data.preferences.CursorStyle
import dev.sumi.terminal.data.preferences.TerminalSettings
import dev.sumi.terminal.ui.components.ColorPalettePicker
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
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
    val palette = LocalWabiSabiPalette.current

    BackHandler(onBack = onDismiss)

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .systemBarsPadding()
            .testTag("appearance_panel_screen")
    ) {
        // Fixed Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Sumi.Sumi2)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("appearance_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar ao Terminal",
                    tint = Sumi.Paper
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Estética Wabi-Sabi • Aparência",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Paletas japonesas, fundo Sumi-e, tipografia e cursor",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
            }
        }

        // Hairline divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        // Scrollable Full-Screen Cards
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Paletas de Cores
            AppearanceSectionCard(
                title = "Paletas de Cores Wabi-Sabi (6 Temas)",
                subtitle = "Tinta mineral, papel Washi e acentos tradicionais"
            ) {
                ColorPalettePicker(
                    selectedPaletteId = settings.paletteName,
                    onSelectPalette = { onSelectPalette(it.id) }
                )
            }

            // Card 2: Papel de Parede e Brilho
            AppearanceSectionCard(
                title = "Papel de Parede e Brilho",
                subtitle = if (settings.customBackgroundPath != null) {
                    "Imagem personalizada ativa"
                } else {
                    "Pintura Sumi-e de Floresta de Bambu (Padrão)"
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Sumi.Sumi3)
                            .border(Sumi.HairlineWidth, Sumi.Kinari.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Selecionar Imagem",
                            color = Sumi.Kinari,
                            fontFamily = Sumi.Mono,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }

                    if (settings.customBackgroundPath != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Sumi.Sumi3)
                                .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(8.dp))
                                .clickable { onSetBackgroundPath(null) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Restaurar Bambu",
                                color = Sumi.Kurenai,
                                fontFamily = Sumi.Mono,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Visibilidade / Brilho do Fundo",
                            color = Sumi.Shironeri,
                            fontFamily = Sumi.Mono,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${(settings.backgroundBrightness * 100).toInt()}%",
                            color = Sumi.Kinari,
                            fontFamily = Sumi.Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
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

            // Card 3: Tipografia e Cursor
            AppearanceSectionCard(
                title = "Tipografia e Cursor",
                subtitle = "JetBrains Mono • Grade de células de alta precisão"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Tamanho da Fonte",
                            color = Sumi.Shironeri,
                            fontFamily = Sumi.Mono,
                            fontSize = 12.sp
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f sp", settings.fontSizeSp),
                            color = Sumi.Kinari,
                            fontFamily = Sumi.Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
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

                Spacer(modifier = Modifier.height(4.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = style.label,
                                    color = if (active) Sumi.Sumi1 else Sumi.Shironeri,
                                    fontFamily = Sumi.Mono,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Card 4: Comportamento
            AppearanceSectionCard(
                title = "Comportamento",
                subtitle = "Realce semântico de sintaxe e energia de tela"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Coloração Semântica de Comandos",
                            color = Sumi.Shironeri,
                            fontFamily = Sumi.Sans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Destaca binários conhecidos e erros de execução",
                            color = Sumi.Muted,
                            fontFamily = Sumi.Mono,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.autoCommandColors,
                        onCheckedChange = onSetAutoColors,
                        colors = SwitchDefaults.colors(checkedThumbColor = Sumi.Kinari)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Sumi.HairlineWidth)
                        .background(Sumi.Hairline)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Manter Tela Sempre Ligada",
                            color = Sumi.Shironeri,
                            fontFamily = Sumi.Sans,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Impede bloqueio da tela durante compilações longas",
                            color = Sumi.Muted,
                            fontFamily = Sumi.Mono,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.keepScreenOn,
                        onCheckedChange = onSetKeepScreenOn,
                        colors = SwitchDefaults.colors(checkedThumbColor = Sumi.Kinari)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppearanceSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Sumi.Sumi2)
            .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Sumi.Kinari,
                fontFamily = Sumi.Mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = subtitle,
                color = Sumi.Muted,
                fontFamily = Sumi.Mono,
                fontSize = 11.sp
            )
        }
        content()
    }
}
