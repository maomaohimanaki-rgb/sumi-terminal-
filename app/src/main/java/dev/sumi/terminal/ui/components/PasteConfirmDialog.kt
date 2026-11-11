package dev.sumi.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sumi.terminal.ui.theme.Sumi

@Composable
fun PasteConfirmDialog(
    text: String,
    onConfirm: (editedText: String, bracketed: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var editableText by remember(text) {
        mutableStateOf(text.replace("\r\n", "\n").replace('\r', '\n'))
    }

    val lineCount = remember(editableText) {
        if (editableText.isEmpty()) 0 else editableText.count { it == '\n' } + 1
    }
    val isMultiLine = lineCount > 1
    val hasDangerCommand = remember(editableText) {
        editableText.contains("rm -rf") || editableText.contains("mkfs") || editableText.contains("dd if=")
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
                        .background(
                            color = if (hasDangerCommand) Sumi.Kurenai.copy(alpha = 0.18f) else Sumi.Kinari.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (hasDangerCommand) "ATENÇÃO" else "SAFE PASTE",
                        color = if (hasDangerCommand) Sumi.Kurenai else Sumi.Kinari,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "Revisar e Editar Colagem",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            Text(
                text = "Edite o comando livremente abaixo antes de enviá-lo ao terminal ($lineCount linha(s), ${editableText.length} caracteres).",
                color = Sumi.Muted,
                fontFamily = Sumi.Sans,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp, max = 240.dp)
                    .background(Sumi.Sumi1, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (hasDangerCommand) Sumi.Kurenai.copy(alpha = 0.7f) else Sumi.Kinari.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    textStyle = TextStyle(
                        color = Sumi.Shironeri,
                        fontFamily = Sumi.Mono,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    cursorBrush = SolidColor(Sumi.Kinari),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("paste_editable_input"),
                    decorationBox = { innerTextField ->
                        if (editableText.isEmpty()) {
                            Text(
                                text = "Digite ou edite o comando para colar...",
                                color = Sumi.Muted,
                                fontFamily = Sumi.Mono,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
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
                    onClick = {
                        if (editableText.isNotEmpty()) {
                            onConfirm(editableText, isMultiLine || editableText.length > 80)
                        } else {
                            onDismiss()
                        }
                    },
                    enabled = editableText.isNotEmpty(),
                    modifier = Modifier.testTag("paste_confirm_button")
                ) {
                    Text(
                        text = if (isMultiLine) "Colar Bloco Editado" else "Confirmar e Colar",
                        color = if (editableText.isNotEmpty()) Sumi.Kinari else Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
