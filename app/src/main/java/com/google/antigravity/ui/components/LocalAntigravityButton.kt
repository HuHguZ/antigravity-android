package com.google.antigravity.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.antigravity.network.LOCAL_ANTIGRAVITY_HUB_COMMAND
import com.google.antigravity.network.LocalHubFailure
import com.google.antigravity.network.checkLocalAntigravityHub
import com.google.antigravity.network.parseLocalAntigravityLaunchUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LocalAntigravityButton(onOpened: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) { Text("Открыть Antigravity") }
    if (showDialog) {
        LocalAntigravityDialog(onDismiss = { showDialog = false }, onOpened = {
            showDialog = false
            onOpened()
        })
    }
}

@Composable
private fun LocalAntigravityDialog(onDismiss: () -> Unit, onOpened: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val url = remember(input) { parseLocalAntigravityLaunchUrl(input) }
    val fieldError = error ?: if (input.isNotBlank() && url == null) {
        "Скопируйте из Termux одну ссылку вместе с частью #t=…"
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Локальный Antigravity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Запустите Hub в Termux. Скопируйте ссылку из вывода команды, например http://localhost:4400/#t=…")
                SelectionContainer {
                    Text(LOCAL_ANTIGRAVITY_HUB_COMMAND, fontFamily = FontFamily.Monospace)
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = input,
                    onValueChange = { value ->
                        input = value
                        error = null
                    },
                    label = { Text("Ссылка запуска") },
                    singleLine = true,
                    enabled = !checking,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = fieldError != null,
                    trailingIcon = {
                        TextButton(enabled = !checking, onClick = {
                            val pasted = clipboard.getText()?.text
                            if (pasted.isNullOrBlank()) {
                                error = "В буфере обмена нет ссылки."
                            } else {
                                input = pasted
                                error = null
                            }
                        }) { Text("Вставить") }
                    },
                    supportingText = fieldError?.let { message -> { Text(message) } }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = url != null && !checking, onClick = {
                val launchUrl = url ?: return@TextButton
                checking = true
                scope.launch {
                    try {
                        error = when (withContext(Dispatchers.IO) { checkLocalAntigravityHub(launchUrl.port) }) {
                            LocalHubFailure.NOT_RUNNING -> "Hub не запущен на порту ${launchUrl.port}."
                            LocalHubFailure.TIMEOUT -> "Hub не ответил вовремя. Проверьте Termux и повторите попытку."
                            LocalHubFailure.UNEXPECTED_RESPONSE -> "На этом порту не удалось получить страницу Antigravity Hub."
                            null -> null
                        }
                        if (error == null) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl.toString()))
                                .addCategory(Intent.CATEGORY_BROWSABLE))
                            onOpened()
                        }
                    } catch (_: ActivityNotFoundException) {
                        error = "Установите браузер, чтобы открыть Antigravity."
                    } finally {
                        checking = false
                    }
                }
            }) { Text(if (checking) "Проверяем…" else "Открыть") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
