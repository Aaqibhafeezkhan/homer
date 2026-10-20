package homer.app.models

import homer.app.apiJson
import homer.app.services.ContinuationStatus
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The DTOs console/types/homer.ts mirrors, for the `dto-contract` check (ADR-0082). Only the roots are
 * listed; every class reachable from one is exported too. A class the console mirrors must be reachable
 * from here — tools/quality/dto-contract.mjs reports a TS type with no exported counterpart.
 */
internal object DtoContract {
    /** Bodies the backend sends: the console's `homerApi` responses and the `llm.event` SSE payload. */
    val responses: List<KSerializer<*>> =
        listOf(
            ProcessListResponse.serializer(),
            ProcessInfoResponse.serializer(),
            ProcessStatusResponse.serializer(),
            ExecuteResponse.serializer(),
            AgentsListResponse.serializer(),
            AgentReloadResult.serializer(),
            LangGraphGraphInfo.serializer(),
            LangGraphRunStatus.serializer(),
            QuestionsListResponse.serializer(),
            QuestionResponse.serializer(),
            ContinuationListResponse.serializer(),
            HeartbeatResponse.serializer(),
            HealthResponse.serializer(),
            CostsResponse.serializer(),
            MonthlyCostsResponse.serializer(),
            TotalCostsResponse.serializer(),
            CostByTagResponse.serializer(),
            RunnersStatusResponse.serializer(),
            RunnerQueueStatusResponse.serializer(),
            AgentFileListResponse.serializer(),
            AgentFileContentResponse.serializer(),
            AgentFileWriteResponse.serializer(),
            CreateAgentResponse.serializer(),
            SchemaErrorResponse.serializer(),
            LoginResponse.serializer(),
            MeResponse.serializer(),
            LlmEvent.serializer(),
        )

    /** Bodies the console sends as a named TS type. */
    val requests: List<KSerializer<*>> =
        listOf(
            DebugAgentRequest.serializer(),
            LoginRequest.serializer(),
        )

    /**
     * Enums whose constant names a response carries in a `String` field (`status = status.name`), so
     * no field reaches them; the console narrows those fields to a union type of the same name.
     */
    val stringEnums: Map<String, List<String>> =
        mapOf(
            "ProcessStatus" to ProcessStatus.entries.map { it.name },
            "PodPhase" to PodPhase.entries.map { it.name },
            "QuestionStatus" to QuestionsService.QuestionStatus.entries.map { it.name },
            "ContinuationStatus" to ContinuationStatus.entries.map { it.name },
        )

    fun export(): JsonObject {
        val walker = DescriptorWalker(apiJson.configuration.classDiscriminator)
        responses.forEach { walker.root(it.descriptor, RESPONSE) }
        requests.forEach { walker.root(it.descriptor, REQUEST) }
        stringEnums.forEach { (name, values) -> walker.enum(name, values, RESPONSE) }
        return walker.toJson()
    }

    const val RESPONSE = "response"
    const val REQUEST = "request"
}

/**
 * Turns serial descriptors into the coarse shape dto-contract.mjs compares: per class its fields with
 * serial name, optional (has a default), nullable and kind. `@Transient` properties have no element,
 * so they never appear. A class is keyed by its simple name, which a file or package move keeps.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class DescriptorWalker(
    private val discriminator: String,
) {
    private class Entry(
        val serialName: String,
        val usages: MutableSet<String>,
    ) {
        var body: JsonObject = JsonObject(emptyMap())
    }

    private val entries = sortedMapOf<String, Entry>()

    fun root(
        descriptor: SerialDescriptor,
        usage: String,
    ) {
        typeOf(descriptor, usage)
    }

    fun enum(
        name: String,
        values: List<String>,
        usage: String,
    ) {
        val entry = claim(name, "enum:$name", usage) ?: return
        entry.body = enumBody(values)
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("discriminator", discriminator)
            put(
                "classes",
                JsonObject(
                    entries.mapValues { (_, entry) ->
                        JsonObject(
                            mapOf(
                                "serialName" to JsonPrimitive(entry.serialName),
                                "usage" to JsonArray(entry.usages.sorted().map(::JsonPrimitive)),
                            ) + entry.body,
                        )
                    },
                ),
            )
        }

    /** The entry to fill for a newly seen class or usage; null when this usage already walked it. */
    private fun claim(
        name: String,
        serialName: String,
        usage: String,
    ): Entry? {
        val existing = entries[name]
        if (existing == null) return Entry(serialName, mutableSetOf(usage)).also { entries[name] = it }
        require(existing.serialName == serialName) {
            "two DTOs share the simple name $name (${existing.serialName}, $serialName): dto-contract matches by it"
        }
        return if (existing.usages.add(usage)) existing else null
    }

    private fun typeOf(
        descriptor: SerialDescriptor,
        usage: String,
    ): JsonObject {
        val serialName = descriptor.serialName.removeSuffix("?")
        return when {
            // JsonElement / JsonObject / JsonArray: any JSON value.
            serialName.startsWith("kotlinx.serialization.json.") -> kind("json")

            descriptor.isInline -> typeOf(descriptor.getElementDescriptor(0), usage)

            else -> kindOf(descriptor, serialName, usage)
        }
    }

    private fun kindOf(
        descriptor: SerialDescriptor,
        serialName: String,
        usage: String,
    ): JsonObject =
        when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> {
                kind("string")
            }

            PrimitiveKind.BOOLEAN -> {
                kind("boolean")
            }

            is PrimitiveKind -> {
                kind("number")
            }

            StructureKind.LIST -> {
                kind("list", "of" to typeOf(descriptor.getElementDescriptor(0), usage))
            }

            StructureKind.MAP -> {
                kind("map", "of" to typeOf(descriptor.getElementDescriptor(1), usage))
            }

            SerialKind.ENUM -> {
                kind("enum", "class" to JsonPrimitive(register(descriptor, serialName, usage)))
            }

            StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                kind("class", "class" to JsonPrimitive(register(descriptor, serialName, usage)))
            }

            // Contextual and open-polymorphic values have no static shape.
            else -> {
                kind("json")
            }
        }

    private fun register(
        descriptor: SerialDescriptor,
        serialName: String,
        usage: String,
    ): String {
        val name = serialName.substringAfterLast('.')
        // Claim before walking the body, so a class that reaches itself stops here.
        val entry = claim(name, serialName, usage) ?: return name
        entry.body =
            when (descriptor.kind) {
                SerialKind.ENUM -> enumBody(descriptor.elementNames.toList())
                PolymorphicKind.SEALED -> sealedBody(descriptor, usage)
                else -> classBody(descriptor, usage)
            }
        return name
    }

    private fun enumBody(values: List<String>): JsonObject =
        buildJsonObject {
            put("kind", "enum")
            putJsonArray("values") { values.forEach { add(JsonPrimitive(it)) } }
        }

    private fun classBody(
        descriptor: SerialDescriptor,
        usage: String,
    ): JsonObject =
        buildJsonObject {
            put("kind", "class")
            put("fields", fields(descriptor, usage))
        }

    // A sealed descriptor has two elements: the discriminator and a "value" whose elements are the subclasses.
    private fun sealedBody(
        descriptor: SerialDescriptor,
        usage: String,
    ): JsonObject =
        buildJsonObject {
            put("kind", "sealed")
            putJsonArray("subclasses") {
                descriptor.getElementDescriptor(1).elementDescriptors.forEach { sub ->
                    add(
                        buildJsonObject {
                            put("serialName", sub.serialName)
                            put("fields", fields(sub, usage))
                        },
                    )
                }
            }
        }

    private fun fields(
        descriptor: SerialDescriptor,
        usage: String,
    ): JsonArray =
        JsonArray(
            (0 until descriptor.elementsCount).map { i ->
                val element = descriptor.getElementDescriptor(i)
                buildJsonObject {
                    put("name", descriptor.getElementName(i))
                    put("optional", descriptor.isElementOptional(i))
                    put("nullable", element.isNullable)
                    put("type", typeOf(element, usage))
                }
            },
        )

    private fun kind(
        kind: String,
        vararg extra: Pair<String, JsonObject>,
    ): JsonObject = JsonObject(mapOf("kind" to JsonPrimitive(kind)) + extra)

    private fun kind(
        kind: String,
        extra: Pair<String, JsonPrimitive>,
    ): JsonObject = JsonObject(mapOf("kind" to JsonPrimitive(kind), extra))
}
