package com.vesper.flipper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.ModelPickerChoices

@Composable
internal fun ModelPicker(
    models: List<ModelInfo>,
    selectedId: String,
    selectedName: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    OutlinedButton(onClick = { query = ""; expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(selectedName)
            Text(selectedId, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (expanded) {
        val results = remember(models, query) { ModelPickerChoices.search(models, query) }
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Choose AI model") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search model name or ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (results.size == ModelPickerChoices.MAX_RESULTS) {
                        Text("Showing up to 40 results. Refine your search for more.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (results.isEmpty()) Text("No matching models")
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(results, key = { it.id }) { model ->
                            TextButton(
                                onClick = { onSelect(model.id); expanded = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(model.displayName)
                                    Text(model.id, style = MaterialTheme.typography.bodySmall)
                                    Text(model.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } }
        )
    }
}
