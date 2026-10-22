package com.vesper.flipper.data

import org.junit.Assert.*
import org.junit.Test

class ModelPickerChoicesTest {
    private val catalog = (1..1000).map { ModelInfo("vendor/model-$it", "Model $it", "Tools") }

    @Test fun `missing active model remains visible without a capability claim`() {
        val choices = ModelPickerChoices.retainSelection(catalog, "old/selected")
        assertEquals(1001, choices.size)
        assertEquals("old/selected", choices.first().id)
        assertTrue(choices.first().displayName.contains("unavailable"))
        assertFalse(choices.first().description.startsWith("Tools"))
        assertEquals(catalog, choices.drop(1))
    }

    @Test fun `present active model keeps verified metadata without duplicates`() {
        val selected = catalog[99]
        val choices = ModelPickerChoices.retainSelection(catalog + selected, selected.id)
        assertEquals(selected, choices.first())
        assertEquals(1000, choices.size)
    }

    @Test fun `search bounds composed choices and reaches late catalog entries`() {
        assertEquals(40, ModelPickerChoices.search(catalog, "").size)
        assertEquals(listOf(catalog.last()), ModelPickerChoices.search(catalog, "  MODEL-1000  "))
        assertTrue(ModelPickerChoices.search(catalog, "absent").isEmpty())
    }
}
