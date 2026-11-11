package dev.sumi.terminal.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ZenEditorScreen(
    file: File,
    onBack: () -> Unit,
    onRunInTerminal: ((File) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    val palette = LocalWabiSabiPalette.current
    var content by remember(file) { mutableStateOf(TextFieldValue("")) }
    var loaded by remember(file) { mutableStateOf(false) }
    var isDirty by remember(file) { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Pronto") }
    val scope = rememberCoroutineScope()
    val verticalScroll = rememberScrollState()

    LaunchedEffect(file) {
        val text = withContext(Dispatchers.IO) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        }
        content = TextFieldValue(text)
        loaded = true
    }

    fun saveFile() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, ".${file.name}.sumi.tmp")
                tmp.writeText(content.text, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(content.text, Charsets.UTF_8)
                    tmp.delete()
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    isDirty = false
                    statusMessage = "Salvo"
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    statusMessage = "Erro ao salvar!"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .testTag("zen_editor_screen")
    ) {
        // Top Bar Zen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(palette.surface)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = Sumi.Paper,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp)
                    .testTag("zen_editor_back_button")
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = Sumi.Paper,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = statusMessage + if (isDirty) " • modificado" else " • ${file.length()} B",
                    color = if (isDirty) palette.primaryAccent else Sumi.Muted,
                    fontSize = 10.sp,
                    fontFamily = Sumi.Mono
                )
            }

            if (onRunInTerminal != null && (file.extension in listOf("sh", "rs", "py") || file.canExecute())) {
                Text(
                    text = "Executar ▸",
                    color = Sumi.Matcha,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Sumi.Matcha.copy(alpha = 0.14f))
                        .clickable {
                            saveFile()
                            onRunInTerminal(file)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("zen_editor_run_button")
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                text = if (isDirty) "Salvar •" else "Salvar",
                color = if (isDirty) palette.primaryAccent else Sumi.Muted,
                fontFamily = Sumi.Mono,
                fontWeight = if (isDirty) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isDirty) palette.primaryAccent.copy(alpha = 0.16f)
                        else palette.background
                    )
                    .border(
                        Sumi.HairlineWidth,
                        if (isDirty) palette.primaryAccent.copy(alpha = 0.6f) else Sumi.Hairline,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { saveFile() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("zen_editor_save_button")
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        // Editor Body com Gutter Dinâmico de Linhas
        if (loaded) {
            val lineCount = remember(content.text) { content.text.count { it == '\n' } + 1 }
            val digits = remember(lineCount) { lineCount.toString().length.coerceAtLeast(2) }
            val gutterWidthDp = remember(digits) { (digits * 9 + 18).dp }
            val gutterText = remember(lineCount, digits) {
                (1..lineCount).joinToString("\n") { it.toString().padStart(digits, ' ') }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(verticalScroll)
            ) {
                Text(
                    text = gutterText,
                    color = Sumi.Muted.copy(alpha = 0.55f),
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(gutterWidthDp)
                        .background(palette.surface.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                )

                Box(
                    modifier = Modifier
                        .width(Sumi.HairlineWidth)
                        .fillMaxSize()
                        .background(Sumi.Hairline)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = {
                            if (it.text != content.text) isDirty = true
                            content = it
                        },
                        textStyle = TextStyle(
                            color = palette.foreground,
                            fontFamily = Sumi.Mono,
                            fontSize = 12.sp,
                            lineHeight = 19.sp
                        ),
                        cursorBrush = SolidColor(palette.primaryAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("zen_editor_text_field")
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        // Barra de Acessórios para Programação
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("zen_editor_accessory_bar"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val symbols = listOf(
                "Tab", "{", "}", "(", ")", "[", "]", "\"", "'", "$", "=", ";", ":", "|", "\\", "/"
            )
            symbols.forEach { sym ->
                Text(
                    text = sym,
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(palette.background)
                        .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(5.dp))
                        .clickable {
                            val ins = if (sym == "Tab") "    " else sym
                            val start = content.selection.min.coerceIn(0, content.text.length)
                            val end = content.selection.max.coerceIn(0, content.text.length)
                            val newText = content.text.replaceRange(start, end, ins)
                            content = content.copy(
                                text = newText,
                                selection = TextRange(start + ins.length)
                            )
                            isDirty = true
                        }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                        .testTag("zen_sym_$sym")
                )
            }
        }
    }
}
