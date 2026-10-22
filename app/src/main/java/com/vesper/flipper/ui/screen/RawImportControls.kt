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
import android.os.CancellationSignal
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RawImportControls(onImport: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var readerActive by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableStateOf(0) }
    var cancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    val activeStream = remember { AtomicReference<InputStream?>(null) }
    val cancelImport: () -> Unit = {
        requestGeneration++
        loading = false
        val signal = cancellation
        val stream = activeStream.getAndSet(null)
        scope.launch(Dispatchers.IO) {
            runCatching { signal?.cancel() }
            runCatching { stream?.close() }
        }
    }
    LaunchedEffect(loading) {
        if (loading) {
            delay(15_000)
            cancelImport()
            onError("Document provider timed out. You can paste RAW text while it closes.")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val signal = cancellation
            val stream = activeStream.getAndSet(null)
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching { signal?.cancel() }
                runCatching { stream?.close() }
            }
        }
    }
    var paste by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            readerActive = true
            val generation = ++requestGeneration
            val signal = CancellationSignal()
            cancellation = signal
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                        descriptor.createInputStream().use { stream ->
                            activeStream.set(stream)
                            signal.throwIfCanceled()
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
                        }
                    } ?: error("Unable to open file")
                }
                if (generation == requestGeneration) onImport(content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == requestGeneration) onError(error.message ?: "RAW import failed")
            } finally {
                activeStream.set(null)
                readerActive = false
                if (generation == requestGeneration) loading = false
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !readerActive) {
            Text(when { loading -> "Importing…"; readerActive -> "Provider closing…"; else -> "Import RAW file" })
        }
        TextButton(onClick = { if (readerActive) cancelImport(); paste = true }) { Text("Paste RAW") }
    }
    if (loading) TextButton(onClick = { cancelImport(); onError("Import cancelled. You can paste RAW text.") }) { Text("Cancel import") }
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
