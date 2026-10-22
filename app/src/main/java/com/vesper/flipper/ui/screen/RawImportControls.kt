package com.vesper.flipper.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.SignalAlchemist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RawImportControls(onImport: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (buffer.size() <= SignalAlchemist.MAX_RAW_CHARACTERS) {
                            val count = stream.read(chunk, 0, minOf(chunk.size, SignalAlchemist.MAX_RAW_CHARACTERS + 1 - buffer.size()))
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                        }
                        val bytes = buffer.toByteArray()
                        require(bytes.size <= SignalAlchemist.MAX_RAW_CHARACTERS) { "RAW file exceeds 1 MiB" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Unable to open file")
                }
                onImport(content)
            } catch (error: Exception) {
                onError(error.message ?: "RAW import failed")
            } finally { loading = false }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !loading) {
            Text(if (loading) "Importing…" else "Import RAW file")
        }
        TextButton(onClick = { paste = true }, enabled = !loading) { Text("Paste RAW") }
    }
    if (paste) AlertDialog(onDismissRequest = { paste = false }, title = { Text("Import SubGHz RAW") },
        text = { OutlinedTextField(value = text, onValueChange = { if (it.length <= SignalAlchemist.MAX_RAW_CHARACTERS) text = it },
            label = { Text("Flipper RAW file content") }, modifier = Modifier.heightIn(max = 300.dp)) },
        confirmButton = { TextButton(onClick = { onImport(text); paste = false }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { paste = false }) { Text("Cancel") } })
}

@Composable
internal fun RawLocalExport(content: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            saving = true
            status = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: error("Unable to write file")
                }
                "RAW file exported"
            } catch (error: Exception) { error.message ?: "Export failed" }
            finally { saving = false }
        }
    }
    OutlinedButton(onClick = { picker.launch("alchemy.sub") }, enabled = !saving) {
        Text(if (saving) "Exporting…" else "Export .sub file")
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
