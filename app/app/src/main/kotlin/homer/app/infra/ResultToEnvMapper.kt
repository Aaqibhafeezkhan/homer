package homer.app.infra

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import homer.app.models.CommandResult
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import kotlin.io.path.readText

class ResultToEnvMapper(
    private val result: CommandResult,
) {
    // LazyThreadSafetyMode.NONE: one mapper per CommandResult, mapToEnv called once
    // from a single coroutine. Don't share a mapper across threads.
    private val stdOutContent by lazy(mode = LazyThreadSafetyMode.NONE) { result.stdOut.readText() }
    private val stdErrContent by lazy(mode = LazyThreadSafetyMode.NONE) { result.stdErr.readText() }
    private val stdOutJsonPath by lazy(mode = LazyThreadSafetyMode.NONE) { parseJsonPathDocument(stdOutContent) }
    private val stdErrJsonPath by lazy(mode = LazyThreadSafetyMode.NONE) { parseJsonPathDocument(stdErrContent) }

    fun mapToEnv(resultEnvParams: List<ResultEnvParam>): Map<String, String> =
        buildMap {
            // Same shed-then-skip contract as the `!<agent>` queryParams transformer: after
            // one regex param is shed, later Regex params are omitted without being
            // evaluated, so the worst-case stall is one bounded match, not one per param.
            // §12 rule 4.
            var regexShed = false
            resultEnvParams.forEach { param ->
                if (regexShed && param.transformer is ResultTransformer.Regex) return@forEach
                val value =
                    when (val transformer = param.transformer) {
                        is ResultTransformer.JsonPath -> {
                            jsonPathValue(param, transformer)
                        }

                        is ResultTransformer.Regex -> {
                            regexValue(param, transformer) { regexShed = true }
                        }

                        ResultTransformer.Pass -> {
                            srcContent(param)
                        }
                    }

                value?.let { put(param.envName, it) }
            }
        }

    private fun srcContent(param: ResultEnvParam): String =
        when (param.src) {
            SrcType.STD_OUT -> stdOutContent
            SrcType.STD_ERR -> stdErrContent
            SrcType.RESULT_CODE -> result.exitCode.toString()
        } ?: ""

    private fun jsonPathValue(
        param: ResultEnvParam,
        transformer: ResultTransformer.JsonPath,
    ): String? {
        val jsonPath =
            when (param.src) {
                SrcType.STD_OUT -> stdOutJsonPath
                SrcType.STD_ERR -> stdErrJsonPath
                SrcType.RESULT_CODE -> null
            }
        return try {
            jsonPath?.read<Any>(transformer.path)?.toString()
        } catch (_: PathNotFoundException) {
            null
        }
    }

    private fun regexValue(
        param: ResultEnvParam,
        transformer: ResultTransformer.Regex,
        onShed: () -> Unit,
    ): String? {
        // Bounded match: the pattern is author-supplied and the content is
        // attacker-influenceable, so a catastrophic backtracker is a ReDoS
        // vector. On timeout (or an uncompilable pattern) the value is omitted
        // rather than pinning a CPU or failing the run. Shared with the
        // `!<agent>` queryParams transformer so the two cannot diverge. §12 rule 4.
        val match = BoundedRegex.findOrOmit(transformer.regex, srcContent(param), param.envName, logger, onShed)
        return match?.let { matchResult ->
            if (transformer.group != null) {
                matchResult.groupValues[transformer.group]
            } else {
                matchResult.value
            }
        }
    }

    /**
     * Builds a JsonPath document from a command's stdout/stderr.
     *
     * Plain single-document JSON (object or array) is handed to JsonPath as-is.
     * Claude's `--output-format stream-json` stdout is NDJSON — one JSON object
     * per line — which is not a single JSON document. We re-assemble those lines
     * into a JSON array so `jsonPath` transformers evaluate against the whole
     * event stream, the same shape the pre-streaming `--output-format json`
     * output had (e.g. `$[?(@.type=='result')].total_cost_usd`). Returns null
     * when neither interpretation parses, so extraction degrades gracefully.
     */
    private fun parseJsonPathDocument(content: String): DocumentContext? {
        if (content.isBlank()) return null
        // A single JSON value (possibly pretty-printed across lines) parses
        // strictly; NDJSON does not — strict parsing rejects the trailing data
        // after the first line's value, which is how we distinguish the two.
        if (runCatching { Json.parseToJsonElement(content) }.isSuccess) {
            return runCatching { JsonPath.parse(content) }.getOrNull()
        }
        return runCatching {
            val events =
                content
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { LlmOutputJson.parseToJsonElement(it) }
                    .toList()
            if (events.isEmpty()) null else JsonPath.parse(JsonArray(events).toString())
        }.getOrNull()
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ResultToEnvMapper::class.java)
    }
}
