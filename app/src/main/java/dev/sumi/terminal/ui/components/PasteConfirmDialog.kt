package dev.sumi.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sumi.terminal.terminal.AnsiConstants
import dev.sumi.terminal.ui.theme.Sumi

@Composable
fun PasteConfirmDialog(
    text: String,
    onConfirm: (bracketed: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val normalized = remember(text) { text.replace("\r\n", "\n").replace('\r', '\n') }
    val lines = remember(normalized) { normalized.split('\n') }
    val lineCount = lines.size
    val isMultiLine = lineCount > 1

    val highlightedPreview = remember(normalized) {
        buildAnnotatedString {
            val previewLines = lines.take(7)
            previewLines.forEachIndexed { idx, line ->
                withStyle(SpanStyle(color = Sumi.Muted)) {
                    append(String.format("%2d │ ", idx + 1))
                }
                val trimmed = line.trimStart()
                val firstToken = trimmed.substringBefore(' ')
                if (firstToken in AnsiConstants.KNOWN_EXECUTABLES) {
                    val leadingSpaces = line.length - trimmed.length
                    if (leadingSpaces > 0) append(line.substring(0, leadingSpaces))
                    withStyle(SpanStyle(color = Sumi.Kinari, fontWeight = FontWeight.Bold)) {
                        append(firstToken)
                    }
                    append(trimmed.removePrefix(firstToken))
                } else if (trimmed.startsWith("#")) {
                    withStyle(SpanStyle(color = Sumi.Muted)) {
                        append(line)
                    }
                } else if (trimmed.contains("rm -rf") || trimmed.contains("sudo ")) {
                    withStyle(SpanStyle(color = Sumi.Kurenai, fontWeight = FontWeight.Bold)) {
                        append(line)
                    }
                } else {
                    withStyle(SpanStyle(color = Sumi.Shironeri)) {
                        append(line)
                    }
                }
                if (idx < previewLines.lastIndex) append('\n')
            }
            if (lines.size > 7) {
                withStyle(SpanStyle(color = Sumi.Muted)) {
                    append("\n   ⋮ (+${lines.size - 7} linhas ocultas)")
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Sumi.Sumi2, RoundedCornerShape(14.dp))
                .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(14.dp))
                .padding(20.dp)
                .testTag("paste_confirm_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Sumi.Kinari.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "SAFE PASTE",
                        color = Sumi.Kinari,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "Confirmar Colagem",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            Text(
                text = "Você está colando $lineCount linha(s) (${text.length} caracteres). O modo Bracketed Paste (\\e[200~...\\e[201~) impede a execução acidental de quebras de linha intermediárias.",
                color = Sumi.Muted,
                fontFamily = Sumi.Sans,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Sumi.Sumi1, RoundedCornerShape(8.dp))
                    .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = highlightedPreview,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("paste_cancel_button")
                ) {
                    Text("Cancelar", color = Sumi.Muted, fontFamily = Sumi.Mono)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onConfirm(isMultiLine || text.length > 80) },
                    modifier = Modifier.testTag("paste_confirm_button")
                ) {
                    Text(
                        text = if (isMultiLine) "Colar como Bloco Seguro" else "Colar",
                        color = Sumi.Kinari,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
