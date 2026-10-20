package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DtoContractTest {
    @Test
    fun `writes the console DTO contract for the dto-contract check`() {
        val export = DtoContract.export()
        val classes = export.getValue("classes").jsonObject
        // The console's process list and the SSE event union are the two widest trees; both must be walked.
        assertTrue("ProcessInfoResponse" in classes && "ExecutionInfoResponse" in classes && "LlmUsage" in classes)
        assertEquals("type", export.getValue("discriminator").jsonPrimitive.content)

        val dir = File(System.getProperty("homer.dtoContract.dir") ?: "build/reports/dto-contract")
        dir.mkdirs()
        dir.resolve("backend-dtos.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), export) + "\n")
    }

    @Test
    fun `exports optional, nullable and coarse kind per field and walks nested classes`() {
        val classes = walk(Fixture.serializer().descriptor)
        val fields = classes.getValue("Fixture").jsonObject.fieldsByName()

        assertEquals(field(optional = false, nullable = false, type = """{"kind":"string"}"""), fields["id"])
        assertEquals(field(optional = true, nullable = true, type = """{"kind":"number"}"""), fields["count"])
        assertEquals(
            field(optional = true, nullable = false, type = """{"kind":"list","of":{"kind":"class","class":"Nested"}}"""),
            fields["items"],
        )
        assertEquals(field(optional = true, nullable = false, type = """{"kind":"map","of":{"kind":"boolean"}}"""), fields["flags"])
        assertEquals(field(optional = false, nullable = false, type = """{"kind":"enum","class":"Color"}"""), fields["color"])
        assertEquals(field(optional = true, nullable = true, type = """{"kind":"json"}"""), fields["raw"])
        assertTrue("cache" !in fields, "@Transient properties are not on the wire")

        assertEquals(
            listOf("red", "BLUE"),
            classes
                .getValue("Color")
                .jsonObject
                .getValue("values")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("response"),
            classes
                .getValue("Nested")
                .jsonObject
                .getValue("usage")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `exports a sealed class with each subclass under its discriminator value`() {
        val shape = walk(Shape.serializer().descriptor).getValue("Shape").jsonObject
        assertEquals("sealed", shape.getValue("kind").jsonPrimitive.content)
        val subclasses = shape.getValue("subclasses").jsonArray.map { it.jsonObject }
        assertEquals(listOf("circle", "dot"), subclasses.map { it.getValue("serialName").jsonPrimitive.content })
        assertEquals(setOf("radius"), subclasses[0].fieldsByName().keys)
        assertEquals(emptySet(), subclasses[1].fieldsByName().keys)
    }

    @Test
    fun `a class reached as request and response carries both usages, walked once each`() {
        val walker = DescriptorWalker("type")
        walker.root(Fixture.serializer().descriptor, DtoContract.RESPONSE)
        walker.root(Nested.serializer().descriptor, DtoContract.REQUEST)
        walker.root(Nested.serializer().descriptor, DtoContract.REQUEST)
        val usage =
            walker
                .toJson()
                .getValue("classes")
                .jsonObject
                .getValue("Nested")
                .jsonObject
                .getValue("usage")
        assertEquals(listOf("request", "response"), usage.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `two DTOs with the same simple name fail the export instead of shadowing each other`() {
        val walker = DescriptorWalker("type")
        walker.enum("Nested", listOf("A"), DtoContract.RESPONSE)
        val error = assertThrows<IllegalArgumentException> { walker.root(Nested.serializer().descriptor, DtoContract.RESPONSE) }
        assertTrue("share the simple name Nested" in error.message.orEmpty())
    }

    private fun walk(descriptor: kotlinx.serialization.descriptors.SerialDescriptor): JsonObject {
        val walker = DescriptorWalker("type")
        walker.root(descriptor, DtoContract.RESPONSE)
        return walker.toJson().getValue("classes").jsonObject
    }

    private fun JsonObject.fieldsByName(): Map<String, JsonElement> =
        getValue("fields").jsonArray.associate { f ->
            f.jsonObject
                .getValue("name")
                .jsonPrimitive.content to JsonObject(f.jsonObject - "name")
        }

    private fun field(
        optional: Boolean,
        nullable: Boolean,
        type: String,
    ): JsonElement = Json.parseToJsonElement("""{"optional":$optional,"nullable":$nullable,"type":$type}""")

    @Serializable
    private enum class Color {
        @SerialName("red")
        RED,
        BLUE,
    }

    @Serializable
    private data class Nested(
        val name: String,
    )

    @Serializable
    private data class Fixture(
        val id: String,
        val count: Int? = null,
        val items: List<Nested> = emptyList(),
        val flags: Map<String, Boolean> = emptyMap(),
        val color: Color,
        val raw: JsonElement? = null,
        @Transient val cache: String = "",
    )

    @Serializable
    private sealed class Shape {
        @Serializable
        @SerialName("circle")
        data class Circle(
            val radius: Double,
        ) : Shape()

        @Serializable
        @SerialName("dot")
        data object Dot : Shape()
    }

    private companion object {
        val prettyJson = Json { prettyPrint = true }
    }
}
