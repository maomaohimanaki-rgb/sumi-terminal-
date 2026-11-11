package dev.sumi.terminal.ui.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import dev.sumi.terminal.install.InstallState
import dev.sumi.terminal.ui.theme.Sumi

@Composable
fun SetupScreen(state: InstallState) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = Sumi.softSpring(),
        label = "setupProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Sumi.Sumi1)
            .padding(28.dp)
            .testTag("setup_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sumi.Sumi2)
                .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        text = "Sumi Terminal",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Arch Linux ARM64 • PRoot & PTY Engine",
                        color = Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontSize = 11.sp
                    )
                }
            }

            Text(
                text = state.phase.title,
                color = Sumi.Kinari,
                fontFamily = Sumi.Mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )

            Text(
                text = state.phase.detail,
                color = Sumi.Shironeri,
                fontFamily = Sumi.Sans,
                fontSize = 13.sp
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Sumi.Sumi1)
                    .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.logLines.takeLast(5).forEach { line ->
                        Text(
                            text = line,
                            color = Sumi.Muted,
                            fontFamily = Sumi.Mono,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
