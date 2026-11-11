package dev.sumi.terminal.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sumi.terminal.perf.ThermalState
import dev.sumi.terminal.service.TerminalSession
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi

@Composable
fun TabBar(
    sessions: List<TerminalSession>,
    activeSessionId: String?,
    thermalState: ThermalState,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenAppearance: () -> Unit
) {
    val palette = LocalWabiSabiPalette.current
    var pendingCloseSession by remember { mutableStateOf<TerminalSession?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand Kanji Stamp (墨)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Sumi.Kurenai.copy(alpha = 0.16f))
                    .border(1.dp, Sumi.Kurenai.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = palette.kanji,
                    color = Sumi.Kurenai,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.width(8.dp))

            // Scrollable horizontal tab list
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_tab_row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    val isActive = session.id == activeSessionId
                    val title by session.title.collectAsState()
                    val isAlive by session.isAlive.collectAsState()
                    val bgColor by animateColorAsState(
                        targetValue = if (isActive) palette.background else palette.surface,
                        animationSpec = Sumi.softSpring(),
                        label = "tabBg"
                    )

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(bgColor)
                            .border(
                                width = Sumi.HairlineWidth,
                                color = if (isActive) palette.primaryAccent.copy(alpha = 0.45f) else Sumi.Hairline,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectTab(session.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("tab_item_${session.index}"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isAlive) Sumi.Matcha else Sumi.Muted)
                            )
                            Text(
                                text = title,
                                color = if (isActive) Sumi.Paper else Sumi.Muted,
                                fontFamily = Sumi.Mono,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                            if (sessions.size > 1) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Fechar aba $title",
                                    tint = Sumi.Muted,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            if (isAlive) {
                                                pendingCloseSession = session
                                            } else {
                                                onCloseTab(session.id)
                                            }
                                        }
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        // Minimalist active tab ink stroke indicator
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(2.dp)
                                .background(
                                    if (isActive) Sumi.Paper else androidx.compose.ui.graphics.Color.Transparent,
                                    RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }

            // Fixed '+' New Tab button on right
            IconButton(
                onClick = onNewTab,
                modifier = Modifier.testTag("new_tab_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Nova aba de terminal",
                    tint = palette.primaryAccent
                )
            }

            // Workspace File Manager & Zen Editor button
            IconButton(
                onClick = onOpenFiles,
                modifier = Modifier.testTag("open_files_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = "Gerenciador de Arquivos",
                    tint = Sumi.Shironeri
                )
            }

            // Appearance & Wabi-Sabi Palette button
            IconButton(
                onClick = onOpenAppearance,
                modifier = Modifier.testTag("open_appearance_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Palette,
                    contentDescription = "Aparência e Paletas Wabi-Sabi",
                    tint = Sumi.Shironeri
                )
            }
        }

        // Hairline separator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )
    }

    pendingCloseSession?.let { target ->
        val tabTitle by target.title.collectAsState()
        AlertDialog(
            onDismissRequest = { pendingCloseSession = null },
            containerColor = Sumi.Sumi2,
            titleContentColor = Sumi.Paper,
            textContentColor = Sumi.Muted,
            title = {
                Text("Encerrar sessão ativa?", fontFamily = Sumi.Sans, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "O processo PTY na aba '$tabTitle' ainda está em execução. Deseja enviar SIGTERM e fechar a aba?",
                    fontFamily = Sumi.Sans,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCloseTab(target.id)
                        pendingCloseSession = null
                    }
                ) {
                    Text("Encerrar", color = Sumi.Kurenai, fontFamily = Sumi.Mono, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseSession = null }) {
                    Text("Cancelar", color = Sumi.Muted, fontFamily = Sumi.Mono)
                }
            }
        )
    }
}
