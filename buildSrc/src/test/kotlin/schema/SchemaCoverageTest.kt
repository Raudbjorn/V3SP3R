package schema
import java.io.File
import kotlinx.serialization.json.*
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
    @Test fun inlineNewEnumEntryCannotEscapeCoverage() {
        val actions = ExecuteCommandSchemaGenerator.parse(ExecuteCommandSchemaGenerator.render())
            .jsonObject.getValue("properties").jsonObject.getValue("action")
            .jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        val body = actions.joinToString("\n") { "@SerialName(\"$it\")\n${it.uppercase()}," }
        val file = File.createTempFile("command", ".kt")
        try {
            file.writeText("enum class CommandAction {\n$body\n@SerialName(\"new_action\") NEW_ACTION,\n}")
            assertThrows(IllegalStateException::class.java) {
                ExecuteCommandSchemaGenerator.enforceCoverage(file)
            }
        } finally { file.delete() }
    }
}
