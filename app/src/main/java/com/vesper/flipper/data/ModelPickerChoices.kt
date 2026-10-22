package com.vesper.flipper.data

/** Display-only choices: retaining a persisted ID never asserts provider capabilities. */
internal object ModelPickerChoices {
    const val MAX_RESULTS = 40

    fun retainSelection(catalog: List<ModelInfo>, selectedId: String): List<ModelInfo> {
        val selected = catalog.firstOrNull { it.id == selectedId } ?: ModelInfo(
            id = selectedId,
            displayName = "$selectedId (unavailable)",
            description = "Not in the current catalog; capabilities unverified"
        )
        return (listOf(selected) + catalog).distinctBy { it.id }
    }

    fun search(choices: List<ModelInfo>, query: String): List<ModelInfo> {
        val term = query.trim()
        return choices.asSequence()
            .filter { term.isEmpty() || it.id.contains(term, ignoreCase = true) ||
                it.displayName.contains(term, ignoreCase = true) }
            .take(MAX_RESULTS)
            .toList()
    }
}
