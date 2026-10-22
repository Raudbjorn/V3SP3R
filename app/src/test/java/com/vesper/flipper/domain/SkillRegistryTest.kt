package com.vesper.flipper.domain
import android.content.Context
import android.content.res.AssetManager
import com.vesper.flipper.domain.service.SkillRegistry
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
class SkillRegistryTest {
    @Test fun catalogAndLoadAreSeparateAndTraversalIsRejected() {
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.assets).thenReturn(assets)
        whenever(assets.list("skills")).thenReturn(arrayOf("signal-analysis"))
        whenever(assets.open("skills/signal-analysis/SKILL.md")).thenAnswer {
            "---\ndescription: Test methodology\n---\nRead only playbook".byteInputStream()
        }
        val registry = SkillRegistry(context)
        assertEquals("Test methodology",registry.list().single().description)
        assertEquals("Read only playbook",registry.load("signal-analysis"))
        assertNull(registry.load("../secret"))
        verify(assets,never()).open("skills/../secret/SKILL.md")
    }
    @Test fun loadIsAssessedAsReadOnly() {
        val risk = com.vesper.flipper.domain.executor.RiskAssessor(mock()).assess(
            com.vesper.flipper.domain.model.ExecuteCommand(com.vesper.flipper.domain.model.CommandAction.LOAD_SKILL,
                com.vesper.flipper.domain.model.CommandArgs(command="campaign"),"Requested methodology","Reference only"))
        assertEquals(com.vesper.flipper.domain.model.RiskLevel.LOW,risk.level)
    }
}
