package homer.agentdebug

import com.charleskorn.kaml.Yaml
import homer.app.config.resolveLlmPrice
import homer.app.infra.SysEnvResolver
import homer.app.models.AppConfig
import kotlinx.serialization.json.Json
import java.io.File

object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }
    private val sysEnvResolver = SysEnvResolver()

    fun loadAppConfig(
        configPath: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): AppConfig {
        val file = File(configPath)
        require(file.exists()) { "Config file not found: $configPath" }
        val raw = file.readText()
        // Must decode the *raw* text: the --env rewrite and resolveMapValues both erase
        // the @SYSENV: placeholders, so this is the only point the names exist.
        val rawConfig = decode(raw, file.extension)
        val sysEnvBackedGlobalEnvNames = sysEnvResolver.secretNamesFor(rawConfig.globalEnv)
        val config =
            if (extraEnv.isEmpty()) {
                rawConfig
            } else {
                val rewritten =
                    raw.replace(Regex("@SYSENV:([A-Za-z_][A-Za-z0-9_]*)")) { match ->
                        val varName = match.groupValues[1]
                        extraEnv[varName] ?: "@SYSENV:$varName"
                    }
                decode(rewritten, file.extension)
            }
        val resolvedGlobalEnv = sysEnvResolver.resolveMapValues(config.globalEnv)
        return config.copy(
            globalEnv = resolvedGlobalEnv,
            llmPrice = resolveLlmPrice(config.llmPrice, sysEnvResolver::resolve),
            sysEnvBackedGlobalEnvNames = sysEnvBackedGlobalEnvNames,
        )
    }

    private fun decode(
        raw: String,
        extension: String,
    ): AppConfig =
        if (extension == "json") {
            json.decodeFromString<AppConfig>(raw)
        } else {
            Yaml.default.decodeFromString(AppConfig.serializer(), raw)
        }

    fun loadEnvFile(path: String): Map<String, String> {
        val file = File(path)
        require(file.exists()) { "Env file not found: $path" }
        return file
            .readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val eq = trimmed.indexOf('=')
                require(eq > 0) { "Invalid line in env file '$path': $trimmed" }
                val key = trimmed.substring(0, eq).trim()
                val raw = trimmed.substring(eq + 1).trim()
                val value =
                    if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                        raw.substring(1, raw.length - 1)
                    } else {
                        raw
                    }
                key to value
            }.toMap()
    }
}
