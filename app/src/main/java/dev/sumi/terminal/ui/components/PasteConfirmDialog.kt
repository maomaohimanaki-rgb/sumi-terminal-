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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import dev.sumi.terminal.ui.theme.Sumi

@Composable
fun PasteConfirmDialog(
    text: String,
    onConfirm: (editedText: String, bracketed: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // For massive pastes (> 3,500 chars or > 80 lines, e.g., 800-line scripts), avoid feeding the entire
    // buffer into a single OutlinedTextField paragraph layout (which blocks the Android UI thread and causes ANRs).
    // Instead, render a virtualized LazyColumn preview and transmit the 100% unmodified text in background blocks.
    val isLargeBuffer = remember(text) { text.length > 3_500 || text.count { it == '\n' } > 80 }
    var editedText by remember(text) { mutableStateOf(text) }
    val previewLines = remember(text, isLargeBuffer) {
        if (isLargeBuffer) text.split('\n') else emptyList()
    }
    val lineCount = remember(editedText, isLargeBuffer, previewLines) {
        if (isLargeBuffer) previewLines.size else editedText.count { it == '\n' } + 1
    }
    val hasDangerCommand = remember(editedText, isLargeBuffer) {
        val sample = if (isLargeBuffer && editedText.length > 8_000) {
            editedText.substring(0, 4_000) + "\n" + editedText.substring(editedText.length - 4_000)
        } else {
            editedText
        }
        val lower = sample.lowercase()
        lower.contains("rm -rf") || lower.contains("mkfs") || lower.contains("dd if=") || lower.contains(":(){")
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sumi.Sumi2)
                .border(
                    width = 1.dp,
                    color = if (hasDangerCommand) Sumi.Kurenai else Sumi.Hairline,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(18.dp)
                .testTag("paste_confirm_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.paste_confirm_title),
                    color = if (hasDangerCommand) Sumi.Kurenai else Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "$lineCount linha(s) • ${editedText.length} chars",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
            }

            if (hasDangerCommand) {
                Text(
                    text = "Atenção: Comando potencialmente destrutivo detectado. Revise antes de colar.",
                    color = Sumi.Kurenai,
                    fontFamily = Sumi.Sans,
                    fontSize = 11.sp
                )
            }

            if (isLargeBuffer) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Sumi1)
                        .border(1.dp, Sumi.Hairline, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .testTag("paste_edit_field"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(previewLines) { idx, line ->
                        Text(
                            text = if (line.length > 240) line.take(240) + "…" else line,
                            color = Sumi.Shironeri,
                            fontFamily = Sumi.Mono,
                            fontSize = 11.sp,
                            maxLines = 2
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    textStyle = TextStyle(
                        color = Sumi.Shironeri,
                        fontFamily = Sumi.Mono,
                        fontSize = 12.sp
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sumi.Kinari,
                        unfocusedBorderColor = Sumi.Hairline,
                        focusedContainerColor = Sumi.Sumi1,
                        unfocusedContainerColor = Sumi.Sumi1,
                        cursorColor = Sumi.Kinari
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 220.dp)
                        .testTag("paste_edit_field")
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Sumi3)
                        .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("paste_cancel_button")
                ) {
                    Text(
                        text = stringResource(R.string.paste_cancel),
                        color = Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Sumi3)
                        .border(Sumi.HairlineWidth, Sumi.Kinari.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable {
                            if (editedText.isNotEmpty()) {
                                onConfirm(editedText, false)
                            } else {
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("paste_raw_button")
                ) {
                    Text(
                        text = stringResource(R.string.paste_simple),
                        color = Sumi.Kinari,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Sumi.Kinari)
                        .clickable {
                            if (editedText.isNotEmpty()) {
                                onConfirm(editedText, true)
                            } else {
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("paste_bracketed_button")
                ) {
                    Text(
                        text = stringResource(R.string.paste_block),
                        color = Sumi.Sumi1,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
