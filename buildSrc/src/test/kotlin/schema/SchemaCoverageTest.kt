package schema
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class SchemaCoverageTest {
    @Test fun unknownActionsFailCoverage() {
        val file = File.createTempFile("command", ".kt")
        try {
            file.writeText("enum class CommandAction {\n@SerialName(\"new_action\")\nNEW_ACTION\n}")
            assertThrows(IllegalStateException::class.java) { ExecuteCommandSchemaGenerator.enforceCoverage(file) }
        } finally { file.delete() }
    }
    @Test fun missingSerialNamesFailClosed() {
        val file = File.createTempFile("command", ".kt")
        try {
            file.writeText("enum class CommandAction {\nUNKNOWN\n}")
            assertThrows(IllegalStateException::class.java) { ExecuteCommandSchemaGenerator.enforceCoverage(file) }
        } finally { file.delete() }
    }
    @Test fun jsonComparisonIgnoresWhitespaceAndKeyOrder() {
        assertEquals(ExecuteCommandSchemaGenerator.parse("{\"a\":1,\"b\":2}"), ExecuteCommandSchemaGenerator.parse("{ \"b\": 2, \"a\": 1 }"))
    }
}
