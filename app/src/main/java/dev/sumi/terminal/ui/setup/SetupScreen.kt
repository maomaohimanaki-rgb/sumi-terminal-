package dev.sumi.terminal.ui.setup

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sumi.terminal.install.InstallPhase
import dev.sumi.terminal.install.InstallState
import dev.sumi.terminal.install.RootfsInstaller
import dev.sumi.terminal.ui.theme.Sumi
import java.util.Locale

@Composable
fun SetupScreen(
    state: InstallState,
    onStartOrRetryInstall: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = Sumi.softSpring(),
        label = "setupProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Sumi.Sumi1)
            .padding(24.dp)
            .testTag("setup_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sumi.Sumi2)
                .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(16.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Sumi.Kurenai.copy(alpha = 0.16f))
                        .border(1.dp, Sumi.Kurenai.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "墨",
                        color = Sumi.Kurenai,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }

                Column {
                    Text(
                        text = "Sumi Terminal • Arch ARM64",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = RootfsInstaller.ARCH_ROOTFS_URL,
                        color = Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.phase.title,
                    color = Sumi.Kinari,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = String.format(Locale.US, "%.1f%%", animatedProgress * 100f),
                    color = Sumi.Paper,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Text(
                text = state.phase.detail,
                color = Sumi.Shironeri,
                fontFamily = Sumi.Sans,
                fontSize = 12.sp
            )

            LinearProgressIndicator(
                progress = { animatedProgress },
                color = Sumi.Kinari,
                trackColor = Sumi.Sumi1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            // Real-time download / extraction telemetry
            if (state.phase == InstallPhase.DOWNLOADING && state.totalBytes > 0L) {
                val dlMb = state.downloadedBytes / (1024f * 1024f)
                val totalMb = state.totalBytes / (1024f * 1024f)
                val speedMb = state.speedBytesPerSec / (1024f * 1024f)
                Text(
                    text = String.format(
                        Locale.US,
                        "Baixado: %.1f MB / %.1f MB  •  %.2f MB/s (HTTP Range Resumível)",
                        dlMb,
                        totalMb,
                        speedMb
                    ),
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
            } else if (state.phase == InstallPhase.EXTRACTING) {
                Text(
                    text = "Nós extraídos: ${state.extractedEntries}  •  /${state.currentEntry}",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            // Error box + Retry with HTTP Range resume
            state.error?.let { errMsg ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Kurenai.copy(alpha = 0.14f))
                        .border(Sumi.HairlineWidth, Sumi.Kurenai, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Falha: $errMsg",
                        color = Sumi.Kurenai,
                        fontFamily = Sumi.Mono,
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Sumi.Kinari)
                                .clickable { onStartOrRetryInstall() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("retry_install_button")
                        ) {
                            Text(
                                text = "Retomar Download (Range)",
                                color = Sumi.Sumi1,
                                fontFamily = Sumi.Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            if (!state.isInstalling && state.error == null && !state.isComplete) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Kinari)
                        .clickable { onStartOrRetryInstall() }
                        .padding(vertical = 12.dp)
                        .testTag("start_install_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Baixar e Instalar Arch Linux ARM64 Oficial",
                        color = Sumi.Sumi1,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            // Log console
            if (state.logLines.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Sumi1)
                        .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.logLines.takeLast(6).forEach { line ->
                            Text(
                                text = line,
                                color = Sumi.Muted,
                                fontFamily = Sumi.Mono,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
