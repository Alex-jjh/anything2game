package com.anything2game.server.config

import com.anything2game.contract.FrameId
import com.anything2game.server.llm.Tier
import java.nio.file.Path
import java.nio.file.Paths

/** Raised at startup when the environment cannot produce a safe, working configuration. */
class ConfigException(message: String) : RuntimeException(message)

/** [OPENAI] is the OpenAI-compatible chat-completions protocol (DeepSeek, Qwen/DashScope, Gemini's compatibility endpoint, ...). */
enum class Provider { FAKE, OPENAI, ANTHROPIC;
    companion object {
        fun parse(name: String, value: String): Provider = when (value.trim().lowercase()) {
            "fake" -> FAKE
            "openai", "openai-compatible", "openai_compatible" -> OPENAI
            "anthropic" -> ANTHROPIC
            else -> throw ConfigException("$name must be one of fake | openai | anthropic (got '$value'; 'openai' means openai-compatible)")
        }
    }
}

enum class StoreKind { FILE, POSTGRES }

/** PostgreSQL connection settings. The password is a [Secret] (it comes from the platform's secret store as an environment variable). */
data class DbConfig(val url: String, val user: String, val password: Secret, val poolSize: Int = 10)

/** Optional sign-in with any OIDC provider (Cognito, Keycloak, Google, ...): access tokens are verified against [jwksUri]. */
data class OidcConfig(val issuer: String, val clientId: String, val jwksUri: String)

/** Settings of one model tier that differ from the shared `A2G_OPENAI_*` / `ANTHROPIC_*` ones. */
data class TierOverrides(val baseUrl: String? = null, val model: String? = null, val apiKey: Secret? = null)

/** What a tier ends up using after the per-tier variables and the shared fallbacks are merged. */
data class TierSettings(val provider: Provider, val baseUrl: String?, val model: String, val apiKey: Secret?)

/** A secret that cannot leak through toString() or string templates. */
class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "***"
}

/** Hard limits of the public API. All can be overridden programmatically (tests); the defaults are the product limits. */
data class Limits(
    val maxPdfBytes: Int = 20 * 1024 * 1024,
    val maxPdfPages: Int = 80,
    val maxTextChars: Int = 200_000,
    val maxTextPages: Int = 500,
    /** Largest request body of any route (PDF upload plus multipart overhead). */
    val maxRequestBytes: Int = 21 * 1024 * 1024,
    /** Largest JSON body of the small routes (compile, ask, installs). */
    val maxSmallJsonBytes: Int = 16 * 1024,
    /** Largest JSON body of a text/OCR document upload (200k chars of CJK text is about 600 KB of UTF-8, plus JSON escaping). */
    val maxTextBodyBytes: Int = 2 * 1024 * 1024,
    val compilesPerInstallPerDay: Int = 20,
    val asksPerInstallPerDay: Int = 200,
    /** Cost guard against install farming: all owners and all server instances together (a counter in the store). */
    val globalCompilesPerDay: Int = 500,
    val installsPerHour: Int = 200,
    val maxQuestionChars: Int = 1000,
)

enum class LogFormat { TEXT, JSON }

data class ServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = 8080,
    val dataDir: Path = Paths.get("server/data"),
    val compileProvider: Provider = Provider.FAKE,
    val frameProvider: Provider = Provider.FAKE,
    val flashProvider: Provider = Provider.FAKE,
    val openAiBaseUrl: String = "https://api.deepseek.com",
    val openAiApiKey: Secret? = null,
    val openAiModelStrong: String = "deepseek-chat",
    val openAiModelFlash: String = "deepseek-chat",
    val anthropicApiKey: Secret? = null,
    val anthropicModel: String = "claude-sonnet-5-5",
    val defaultFrames: List<FrameId> = listOf(FrameId.CASE),
    val llmTimeoutSeconds: Long = 120,
    val askTimeoutMillis: Long = 5_000,
    val limits: Limits = Limits(),
    /** `A2G_<COMPILE|FRAME|FLASH>_BASE_URL / _MODEL / _API_KEY`; whatever is missing falls back to the shared settings above. */
    val tierOverrides: Map<Tier, TierOverrides> = emptyMap(),
    val storeKind: StoreKind = StoreKind.FILE,
    val db: DbConfig? = null,
    val oidc: OidcConfig? = null,
    val pdfTimeoutSeconds: Int = 20,
    val logFormat: LogFormat = LogFormat.TEXT,
    /** Job queue: how long a worker owns a job without renewing, how often it polls, how many jobs one instance runs at once. */
    val workerLeaseMillis: Long = 30_000,
    val workerPollMillis: Long = 1_000,
    val workerConcurrency: Int = 2,
) {
    private fun providerOf(tier: Tier) = when (tier) {
        Tier.COMPILE -> compileProvider
        Tier.FRAME -> frameProvider
        Tier.FLASH -> flashProvider
    }

    /** The provider, endpoint, model and key a tier uses: its own `A2G_<TIER>_*` values first, then the shared ones. */
    fun tier(tier: Tier): TierSettings {
        val o = tierOverrides[tier] ?: TierOverrides()
        return when (val provider = providerOf(tier)) {
            Provider.FAKE -> TierSettings(provider, null, "fake-llm", null)
            Provider.OPENAI -> TierSettings(
                provider, (o.baseUrl ?: openAiBaseUrl).trimEnd('/'),
                o.model ?: if (tier == Tier.FLASH) openAiModelFlash else openAiModelStrong, o.apiKey ?: openAiApiKey,
            )
            Provider.ANTHROPIC -> TierSettings(provider, o.baseUrl?.trimEnd('/'), o.model ?: anthropicModel, o.apiKey ?: anthropicApiKey)
        }
    }

    val isLoopback: Boolean get() = host == "127.0.0.1" || host == "localhost" || host == "::1"

    companion object {
        /** Never an all-interfaces address: exposing the server is an explicit decision (A2G_HOST) behind a TLS reverse proxy. */
        const val DEFAULT_HOST = "127.0.0.1"

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun get(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(name: String, default: Int, range: IntRange): Int {
                val raw = get(name) ?: return default
                val v = raw.toIntOrNull() ?: throw ConfigException("$name must be an integer (got '$raw')")
                if (v !in range) throw ConfigException("$name must be in $range (got $v)")
                return v
            }

            /** `A2G_<TIER>_PROVIDER` wins over the older `A2G_LLM_<TIER>`. */
            fun provider(tier: String): Provider {
                val name = if (get("A2G_${tier}_PROVIDER") != null) "A2G_${tier}_PROVIDER" else "A2G_LLM_$tier"
                return get(name)?.let { Provider.parse(name, it) } ?: Provider.FAKE
            }
            fun overrides(tier: String) = TierOverrides(
                baseUrl = get("A2G_${tier}_BASE_URL"), model = get("A2G_${tier}_MODEL"), apiKey = get("A2G_${tier}_API_KEY")?.let(::Secret),
            )

            val d = ServerConfig()
            // Cloud Run, App Runner and Heroku-style platforms hand the port over as PORT; A2G_PORT wins when both are set.
            val portName = if (get("A2G_PORT") == null && get("PORT") != null) "PORT" else "A2G_PORT"
            val storeKind = when (val v = get("A2G_STORE")?.lowercase() ?: "file") {
                "file" -> StoreKind.FILE
                "postgres", "postgresql" -> StoreKind.POSTGRES
                else -> throw ConfigException("A2G_STORE must be file | postgres (got '$v')")
            }
            val config = ServerConfig(
                host = get("A2G_HOST") ?: DEFAULT_HOST,
                port = int(portName, d.port, 0..65535),
                dataDir = Paths.get(get("A2G_DATA_DIR") ?: "server/data"),
                compileProvider = provider("COMPILE"),
                frameProvider = provider("FRAME"),
                flashProvider = provider("FLASH"),
                tierOverrides = mapOf(Tier.COMPILE to overrides("COMPILE"), Tier.FRAME to overrides("FRAME"), Tier.FLASH to overrides("FLASH")),
                storeKind = storeKind,
                db = if (storeKind == StoreKind.POSTGRES) dbConfig(::get, int("A2G_DB_POOL_SIZE", 10, 3..100)) else null,
                oidc = get("A2G_OIDC_ISSUER")?.let { oidcConfig(it, ::get) },
                pdfTimeoutSeconds = int("A2G_PDF_TIMEOUT_SECONDS", d.pdfTimeoutSeconds, 1..300),
                logFormat = when (val v = get("A2G_LOG_FORMAT")?.lowercase() ?: "text") {
                    "text" -> LogFormat.TEXT
                    "json" -> LogFormat.JSON
                    else -> throw ConfigException("A2G_LOG_FORMAT must be text | json (got '$v')")
                },
                workerLeaseMillis = int("A2G_WORKER_LEASE_SECONDS", (d.workerLeaseMillis / 1000).toInt(), 5..3600) * 1000L,
                workerPollMillis = int("A2G_WORKER_POLL_MS", d.workerPollMillis.toInt(), 50..60_000).toLong(),
                workerConcurrency = int("A2G_WORKER_CONCURRENCY", d.workerConcurrency, 1..16),
                openAiBaseUrl = (get("A2G_OPENAI_BASE_URL") ?: d.openAiBaseUrl).trimEnd('/'),
                openAiApiKey = get("A2G_OPENAI_API_KEY")?.let(::Secret),
                openAiModelStrong = get("A2G_OPENAI_MODEL_STRONG") ?: d.openAiModelStrong,
                openAiModelFlash = get("A2G_OPENAI_MODEL_FLASH") ?: d.openAiModelFlash,
                anthropicApiKey = get("ANTHROPIC_API_KEY")?.let(::Secret),
                anthropicModel = get("A2G_ANTHROPIC_MODEL") ?: d.anthropicModel,
                defaultFrames = parseFrames(get("A2G_DEFAULT_FRAMES") ?: "case"),
                llmTimeoutSeconds = int("A2G_LLM_TIMEOUT_SECONDS", d.llmTimeoutSeconds.toInt(), 5..900).toLong(),
                askTimeoutMillis = int("A2G_ASK_TIMEOUT_MS", d.askTimeoutMillis.toInt(), 200..60_000).toLong(),
                limits = Limits(
                    globalCompilesPerDay = int("A2G_GLOBAL_COMPILES_PER_DAY", d.limits.globalCompilesPerDay, 1..1_000_000),
                    installsPerHour = int("A2G_INSTALLS_PER_HOUR", d.limits.installsPerHour, 1..1_000_000),
                ),
            )
            config.validate()
            return config
        }

        private fun dbConfig(get: (String) -> String?, poolSize: Int): DbConfig {
            val missing = listOf("A2G_DB_URL", "A2G_DB_USER", "A2G_DB_PASSWORD").filter { get(it) == null }
            if (missing.isNotEmpty()) {
                throw ConfigException(
                    "A2G_STORE=postgres needs A2G_DB_URL (jdbc:postgresql://host:5432/dbname), A2G_DB_USER and A2G_DB_PASSWORD; missing: ${missing.joinToString()}. " +
                        "Inject the password from the platform's secret store as an environment variable.",
                )
            }
            val url = get("A2G_DB_URL")!!
            if (!url.startsWith("jdbc:postgresql:")) throw ConfigException("A2G_DB_URL must be a PostgreSQL JDBC URL starting with jdbc:postgresql: (got a different scheme)")
            return DbConfig(url, get("A2G_DB_USER")!!, Secret(get("A2G_DB_PASSWORD")!!), poolSize)
        }

        private fun oidcConfig(issuer: String, get: (String) -> String?): OidcConfig {
            val clientId = get("A2G_OIDC_CLIENT_ID")
                ?: throw ConfigException("A2G_OIDC_ISSUER is set, so A2G_OIDC_CLIENT_ID (the app client id the tokens are issued for) is required.")
            val iss = issuer.trimEnd('/')
            // Cognito publishes its keys at <issuer>/.well-known/jwks.json; other providers: set A2G_OIDC_JWKS_URI.
            val jwks = get("A2G_OIDC_JWKS_URI") ?: "$iss/.well-known/jwks.json"
            for ((name, url) in listOf("A2G_OIDC_ISSUER" to iss, "A2G_OIDC_JWKS_URI" to jwks)) {
                if (!isHttpsOrLocal(url)) throw ConfigException("$name must be https:// (http only for localhost).")
            }
            return OidcConfig(iss, clientId, jwks)
        }

        private fun isHttpsOrLocal(url: String): Boolean {
            val u = url.lowercase()
            return u.startsWith("https://") || u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1")
        }

        private fun parseFrames(raw: String): List<FrameId> = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { name ->
            FrameId.entries.firstOrNull { frameName(it) == name }
                ?: throw ConfigException("A2G_DEFAULT_FRAMES: unknown frame '$name' (known: ${FrameId.entries.joinToString { frameName(it) }})")
        }.distinct()

        /** Wire name of a frame (the @SerialName in the contract). */
        fun frameName(id: FrameId): String = id.name.lowercase()
    }

    /** Fails fast: a tier that selects a real provider must have that provider's key. Keys come from the environment only. */
    fun validate() {
        val names = mapOf(Tier.COMPILE to "A2G_LLM_COMPILE", Tier.FRAME to "A2G_LLM_FRAME", Tier.FLASH to "A2G_LLM_FLASH")
        for (tier in Tier.entries) {
            val t = tier(tier)
            val label = "${names.getValue(tier)} (A2G_${tier.name}_PROVIDER)"
            val key = t.apiKey
            when (t.provider) {
                Provider.OPENAI -> if (key == null || key.reveal().isBlank()) {
                    throw ConfigException("$label=openai but no API key is set. Set A2G_${tier.name}_API_KEY or A2G_OPENAI_API_KEY in the environment, or use fake.")
                }
                Provider.ANTHROPIC -> if (key == null || key.reveal().isBlank()) {
                    throw ConfigException("$label=anthropic but no API key is set. Set A2G_${tier.name}_API_KEY or ANTHROPIC_API_KEY in the environment, or use fake.")
                }
                Provider.FAKE -> Unit
            }
            val url = t.baseUrl
            if (t.provider != Provider.FAKE && url != null && !isHttpsOrLocal(url)) {
                throw ConfigException("The base URL of tier ${tier.name.lowercase()} (A2G_OPENAI_BASE_URL or A2G_${tier.name}_BASE_URL) must be https:// (the API key is sent to it); http is only accepted for localhost.")
            }
        }
        if (storeKind == StoreKind.POSTGRES && db == null) throw ConfigException("A2G_STORE=postgres needs the database settings (A2G_DB_URL, A2G_DB_USER, A2G_DB_PASSWORD).")
    }
}
