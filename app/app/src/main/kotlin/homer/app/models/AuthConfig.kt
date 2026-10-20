package homer.app.models

import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable(with = TokenConfigSerializer::class)
data class TokenConfig(
    val token: String,
    override val role: String? = null,
    override val grants: List<Grant> = emptyList(),
    override val endpoints: List<String>? = null,
) : Principaled {
    init {
        // A blank token would match the empty credential the bearer provider
        // extracts from `Authorization: Bearer `, authenticating anyone.
        // resolveAuth rebuilds via copy(), so this also rejects @SYSENV
        // values that resolve to blank.
        require(token.isNotBlank()) { "TokenConfig.token must not be blank." }
        requireValidPrincipalGrants(role, grants, endpoints, "TokenConfig")
    }

    override fun toString() = "TokenConfig(token=${redactSecret(token)}, role=$role, grants=$grants)"
}

@Serializable
private data class TokenConfigObject(
    val token: String,
    val role: String? = null,
    val grants: List<Grant> = emptyList(),
    val endpoints: List<String>? = null,
)

private object ScalarTokenConfigSerializer : KSerializer<TokenConfig> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TokenConfig.scalar", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder) = TokenConfig(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: TokenConfig,
    ) = encoder.encodeString(value.token)
}

private object FullTokenConfigSerializer : KSerializer<TokenConfig> {
    private val delegate = TokenConfigObject.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): TokenConfig {
        val obj = delegate.deserialize(decoder)
        return TokenConfig(obj.token, obj.role, obj.grants, obj.endpoints)
    }

    override fun serialize(
        encoder: Encoder,
        value: TokenConfig,
    ) {
        delegate.serialize(encoder, TokenConfigObject(value.token, value.role, value.grants, value.endpoints))
    }
}

object TokenConfigSerializer : YamlContentPolymorphicSerializer<TokenConfig>(TokenConfig::class) {
    override fun selectDeserializer(node: YamlNode): DeserializationStrategy<TokenConfig> =
        when (node) {
            is YamlScalar -> ScalarTokenConfigSerializer
            else -> FullTokenConfigSerializer
        }

    override fun deserialize(decoder: Decoder): TokenConfig {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            return when {
                element is JsonPrimitive -> {
                    TokenConfig(element.content)
                }

                element is JsonObject -> {
                    val obj = decoder.json.decodeFromJsonElement(TokenConfigObject.serializer(), element)
                    TokenConfig(obj.token, obj.role, obj.grants, obj.endpoints)
                }

                else -> {
                    throw SerializationException("Unexpected JSON element for TokenConfig: $element")
                }
            }
        }
        return super.deserialize(decoder)
    }
}

@Serializable
enum class Action {
    @SerialName("run")
    RUN,

    @SerialName("read")
    READ,

    @SerialName("control")
    CONTROL,

    @SerialName("edit")
    EDIT,

    @SerialName("debug")
    DEBUG,
}

/**
 * A grant over one or more agents (name glob patterns, `*` = all) and the
 * actions permitted on them. Agent-name matching reuses the same glob rules
 * the endpoint matcher used for paths. See ADR-0037.
 */
@Serializable
data class Grant(
    val agents: List<String>,
    val actions: List<Action>,
) {
    init {
        require(agents.isNotEmpty()) { "Grant.agents must not be empty." }
        require(actions.isNotEmpty()) { "Grant.actions must not be empty." }
    }

    fun matches(
        agentName: String,
        action: Action,
    ): Boolean = action in actions && agents.any { AgentNameMatcher.matches(it, agentName) }
}

internal object AgentNameMatcher {
    fun matches(
        pattern: String,
        name: String,
    ): Boolean =
        when {
            pattern == "*" -> true
            pattern.endsWith("*") -> name.startsWith(pattern.dropLast(1))
            else -> pattern == name
        }
}

/** Common authorization surface for the three principal kinds. */
interface Principaled {
    val role: String?
    val grants: List<Grant>

    /** Deprecated path allowlist; non-null means the config must be migrated (rejected at startup). */
    val endpoints: List<String>?
}

/**
 * A principal must declare exactly one authority form: `role: admin`, or a
 * non-empty `grants` list. A leftover `endpoints` field is a not-yet-migrated
 * config; it is tolerated at parse time and rejected at startup with a
 * migration hint (validateNoLegacyEndpoints) so the message can name the fix.
 */
internal fun requireValidPrincipalGrants(
    role: String?,
    grants: List<Grant>,
    endpoints: List<String>?,
    who: String,
) {
    if (role != null) {
        require(role == "admin") { "$who.role must be 'admin' if set (got '$role')." }
    }
    // endpoints present → defer to startup migration error; grants emptiness is
    // checked at startup too (a principal may legitimately parse with neither
    // here so the startup error can aggregate and instruct).
}

@Serializable
sealed class AuthConfig {
    @Serializable
    @SerialName("bearer")
    data class Bearer(
        val tokens: Map<String, TokenConfig>,
    ) : AuthConfig() {
        init {
            // A bearer token's map key becomes its UserIdPrincipal name verbatim.
            // "session:" is reserved for cookie principals (the CSRF guard keys on
            // it), "hmac:" for HMAC key principals in a combined config, and
            // "system:" for internal synthetic owners (e.g. "system:cron"); a
            // bearer key with any of these prefixes would collide with that
            // principal's namespace. Keep principal namespaces disjoint. See ADR-0016.
            tokens.keys.forEach { key ->
                require(!key.startsWith("session:")) {
                    "AuthConfig.Bearer token key '$key' must not start with the reserved 'session:' prefix " +
                        "(claimed by cookie/session auth; see ADR-0016)."
                }
                require(!key.startsWith("hmac:")) {
                    "AuthConfig.Bearer token key '$key' must not start with the reserved 'hmac:' prefix " +
                        "(claimed by HMAC key principals; see ADR-0016)."
                }
                require(!key.startsWith("system:")) {
                    "AuthConfig.Bearer token key '$key' must not start with the reserved 'system:' prefix " +
                        "(claimed by internal synthetic owners such as 'system:cron'; see ADR-0016)."
                }
            }
        }
    }

    @Serializable
    @SerialName("hmac")
    data class Hmac(
        val keys: Map<String, HmacKeyConfig>,
    ) : AuthConfig()

    /**
     * Username + password authentication backed by an HMAC-signed `HttpOnly`
     * session cookie. Closes audit P0 #6 (long-term phase) — see ADR-0011.
     *
     * `sessionSecret` (and the optional `sessionSecretPrevious` rotation key)
     * sign the cookie payload; both flow through `SysEnvResolver` like
     * `HmacKeyConfig.secret`. `sessionTtlMin` is the cookie's `Max-Age` and
     * the `exp` claim in the payload.
     *
     * Per-user `role`/`grants` follow the same authorization model as
     * `Bearer` tokens (ADR-0037), so a single deployment can serve a
     * tightly-scoped console session alongside broader CLI bearer tokens.
     */
    @Serializable
    @SerialName("password")
    data class Password(
        val users: Map<String, PasswordUserConfig>,
        val sessionSecret: String,
        val sessionSecretPrevious: String? = null,
        val sessionTtlMin: Int = 720,
        val cookieName: String = "homer_session",
        val cookieSecure: Boolean = true,
        val cookieSameSite: SameSiteMode = SameSiteMode.LAX,
        val bcryptCost: Int = 12,
    ) : AuthConfig() {
        init {
            require(users.isNotEmpty()) { "AuthConfig.Password.users must not be empty." }
            require(sessionSecret.isNotBlank()) { "AuthConfig.Password.sessionSecret must not be blank." }
            require(sessionTtlMin > 0) { "AuthConfig.Password.sessionTtlMin must be > 0." }
            require(bcryptCost in 4..15) { "AuthConfig.Password.bcryptCost must be in 4..15." }
            require(cookieName.isNotBlank()) { "AuthConfig.Password.cookieName must not be blank." }
            // SameSite=None cookies are dropped by browsers unless Secure is set.
            require(cookieSameSite != SameSiteMode.NONE || cookieSecure) {
                "AuthConfig.Password.cookieSameSite=NONE requires cookieSecure=true (browsers reject " +
                    "SameSite=None without Secure). See ADR-0028."
            }
        }

        override fun toString() =
            "Password(users=$users, sessionSecret=${redactSecret(sessionSecret)}, " +
                "sessionSecretPrevious=${redactSecret(sessionSecretPrevious)}, sessionTtlMin=$sessionTtlMin, " +
                "cookieName=$cookieName, cookieSecure=$cookieSecure, cookieSameSite=$cookieSameSite, bcryptCost=$bcryptCost)"
    }

    @Serializable
    @SerialName("combined")
    data class Combined(
        val schemes: List<AuthConfig>,
    ) : AuthConfig() {
        init {
            require(schemes.isNotEmpty()) {
                "AuthConfig.Combined.schemes must not be empty."
            }
            require(schemes.none { it is Combined }) {
                "AuthConfig.Combined cannot nest another Combined; flatten the list."
            }
            require(schemes.none { it is Unsecured }) {
                "AuthConfig.Combined cannot include Unsecured."
            }
            require(
                schemes.count { it is Bearer } <= 1 &&
                    schemes.count { it is Hmac } <= 1 &&
                    schemes.count { it is Password } <= 1,
            ) {
                "AuthConfig.Combined.schemes may contain at most one Bearer, one Hmac, and one Password entry."
            }
        }
    }

    @Serializable
    @SerialName("unsecured")
    object Unsecured : AuthConfig()
}

/**
 * Unwrap [AuthConfig.Combined] / [AuthConfig.Unsecured] into the concrete schemes in
 * force. `null` (config omitted `apiAuth`) has no schemes — [homer.app.validateExplicitApiAuth]
 * rejects it at startup before any scheme is consulted.
 */
internal fun flattenAuthSchemes(auth: AuthConfig?): List<AuthConfig> =
    when (auth) {
        null -> emptyList()
        is AuthConfig.Combined -> auth.schemes
        is AuthConfig.Unsecured -> emptyList()
        else -> listOf(auth)
    }

@Serializable
data class PasswordUserConfig(
    val passwordHash: String,
    override val role: String? = null,
    override val grants: List<Grant> = emptyList(),
    override val endpoints: List<String>? = null,
) : Principaled {
    init {
        require(passwordHash.isNotBlank()) { "PasswordUserConfig.passwordHash must not be blank." }
        requireValidPrincipalGrants(role, grants, endpoints, "PasswordUserConfig")
    }

    override fun toString() =
        "PasswordUserConfig(passwordHash=${redactSecret(passwordHash)}, role=$role, grants=$grants, endpoints=$endpoints)"
}

@Serializable
data class HmacKeyConfig(
    val secret: String,
    override val role: String? = null,
    override val grants: List<Grant> = emptyList(),
    override val endpoints: List<String>? = null,
    val signatureHeader: String = "X-Homer-Signature",
    val algorithm: HmacAlgorithm = HmacAlgorithm.SHA256,
    val encoding: HmacEncoding = HmacEncoding.HEX,
    val prefix: String = "",
    val payloadTemplate: String = "{{body}}",
    val timestampHeader: String? = null,
    val replayToleranceSec: Long = 300,
) : Principaled {
    init {
        // A blank secret is a public HMAC key — any client could compute
        // valid signatures. resolveAuth rebuilds via copy(), so this also
        // rejects @SYSENV values that resolve to blank.
        require(secret.isNotBlank()) { "HmacKeyConfig.secret must not be blank" }
        require(signatureHeader.isNotBlank()) {
            "HmacKeyConfig.signatureHeader must not be blank"
        }
        require(replayToleranceSec > 0) {
            "HmacKeyConfig.replayToleranceSec must be > 0; got $replayToleranceSec"
        }
        val knownTokens = setOf("{{body}}", "{{timestamp}}", "{{method}}", "{{path}}", "{{query}}")
        val used =
            Regex("\\{\\{[^}]+}}")
                .findAll(payloadTemplate)
                .map { it.value }
                .toSet()
        val unknown = used - knownTokens
        require(unknown.isEmpty()) {
            "HmacKeyConfig.payloadTemplate contains unknown tokens: $unknown. Allowed: $knownTokens"
        }
        require(timestampHeader != null || "{{timestamp}}" !in used) {
            "HmacKeyConfig.payloadTemplate uses {{timestamp}} but timestampHeader is null"
        }
        // The mirror rule: a freshness-checked timestamp must also be
        // MAC-bound, or a captured signature can be replayed forever with
        // a fresh (unsigned) header value.
        require(timestampHeader == null || "{{timestamp}}" in used) {
            "HmacKeyConfig.timestampHeader is set but payloadTemplate does not include {{timestamp}}; " +
                "an unsigned timestamp provides no replay protection (e.g. use \"{{timestamp}}.{{body}}\")"
        }
        requireValidPrincipalGrants(role, grants, endpoints, "HmacKeyConfig")
    }

    override fun toString() =
        "HmacKeyConfig(secret=${redactSecret(secret)}, role=$role, grants=$grants, endpoints=$endpoints, " +
            "signatureHeader=$signatureHeader, algorithm=$algorithm, encoding=$encoding, prefix=$prefix, " +
            "payloadTemplate=$payloadTemplate, timestampHeader=$timestampHeader, replayToleranceSec=$replayToleranceSec)"
}

@Serializable
enum class HmacAlgorithm {
    @SerialName("sha1")
    SHA1,

    @SerialName("sha256")
    SHA256,

    @SerialName("sha512")
    SHA512,
    ;

    val javaName: String get() = "Hmac$name" // HmacSHA1 / HmacSHA256 / HmacSHA512
}

/**
 * `SameSite` attribute applied to the session cookie issued by
 * [AuthConfig.Password]. `LAX` (default) preserves the original single-origin
 * console behavior. `NONE` is required when the console is served from a
 * different origin than the API (cross-origin cookie auth) and is only valid
 * alongside `cookieSecure = true`. Moving off `LAX` does not reopen CSRF —
 * the defense is the `X-Homer-CSRF` custom-header guard plus pinned
 * credentialed CORS (ADR-0016), not SameSite. See ADR-0028.
 */
@Serializable
enum class SameSiteMode {
    @SerialName("lax")
    LAX,

    @SerialName("none")
    NONE,

    @SerialName("strict")
    STRICT,
    ;

    /** The literal value written to the cookie's `SameSite` attribute. */
    val cookieValue: String
        get() =
            when (this) {
                LAX -> "Lax"
                NONE -> "None"
                STRICT -> "Strict"
            }
}

@Serializable
enum class HmacEncoding {
    @SerialName("hex")
    HEX,

    @SerialName("base64")
    BASE64,
}
