package com.vesper.flipper.data

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun `keeps every tool capable model and tolerates malformed entries`() {
        val models = OpenRouterModelCatalog().parseCatalog("""{"data":[
          null, 3,
          {"id":"openai/a","supported_parameters":["tools"],"created":1,"architecture":3},
          {"id":"openai/b","supported_parameters":["tools"],"created":2},
          {"id":"openai/image","supported_parameters":["images"]},
          {"id":"openai/b:free","supported_parameters":["tools"]}
        ]}""")
        assertEquals(listOf("openai/b", "openai/a"), models.map { it.id })
    }
    @Test fun `offline catalog never selects an image only default`() {
        assertFalse(SettingsStore.FALLBACK_MODELS.any { it.id.contains("flash-image") })
    }
}
