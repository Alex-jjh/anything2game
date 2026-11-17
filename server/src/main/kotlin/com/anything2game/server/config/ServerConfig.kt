package com.anything2game.server.config

import com.anything2game.contract.FrameId
import java.nio.file.Path
import java.nio.file.Paths

/** Raised at startup when the environment cannot produce a safe, working configuration. */
class ConfigException(message: String) : RuntimeException(message)

enum class Provider { FAKE, OPENAI, ANTHROPIC;
    companion object {
        fun parse(name: String, value: String): Provider = when (value.trim().lowercase()) {
            "fake" -> FAKE
            "openai" -> OPENAI
            "anthropic" -> ANTHROPIC
            else -> throw ConfigException("$name must be one of fake | openai | anthropic (got '$value')")
        }
    }
}

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
    /** Cost guard against install farming: all installs together. In-memory, resets on restart. */
    val globalCompilesPerDay: Int = 500,
    val installsPerHour: Int = 200,
    val maxQuestionChars: Int = 1000,
)

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
) {
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

            val d = ServerConfig()
            val config = ServerConfig(
                host = get("A2G_HOST") ?: DEFAULT_HOST,
                port = int("A2G_PORT", d.port, 0..65535),
                dataDir = Paths.get(get("A2G_DATA_DIR") ?: "server/data"),
                compileProvider = get("A2G_LLM_COMPILE")?.let { Provider.parse("A2G_LLM_COMPILE", it) } ?: Provider.FAKE,
                frameProvider = get("A2G_LLM_FRAME")?.let { Provider.parse("A2G_LLM_FRAME", it) } ?: Provider.FAKE,
                flashProvider = get("A2G_LLM_FLASH")?.let { Provider.parse("A2G_LLM_FLASH", it) } ?: Provider.FAKE,
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

        private fun parseFrames(raw: String): List<FrameId> = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { name ->
            FrameId.entries.firstOrNull { frameName(it) == name }
                ?: throw ConfigException("A2G_DEFAULT_FRAMES: unknown frame '$name' (known: ${FrameId.entries.joinToString { frameName(it) }})")
        }.distinct()

        /** Wire name of a frame (the @SerialName in the contract). */
        fun frameName(id: FrameId): String = id.name.lowercase()
    }

    /** Fails fast: a tier that selects a real provider must have that provider's key. Keys come from the environment only. */
    fun validate() {
        val tiers = mapOf("A2G_LLM_COMPILE" to compileProvider, "A2G_LLM_FRAME" to frameProvider, "A2G_LLM_FLASH" to flashProvider)
        tiers.forEach { (name, provider) ->
            when (provider) {
                Provider.OPENAI -> if (openAiApiKey == null || openAiApiKey.reveal().isBlank()) {
                    throw ConfigException("$name=openai but A2G_OPENAI_API_KEY is not set. Set the key in the environment or use A2G_LLM_*=fake.")
                }
                Provider.ANTHROPIC -> if (anthropicApiKey == null || anthropicApiKey.reveal().isBlank()) {
                    throw ConfigException("$name=anthropic but ANTHROPIC_API_KEY is not set. Set the key in the environment or use A2G_LLM_*=fake.")
                }
                Provider.FAKE -> Unit
            }
        }
        if (tiers.values.any { it == Provider.OPENAI }) {
            val url = openAiBaseUrl.lowercase()
            val local = url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")
            if (!url.startsWith("https://") && !local) {
                throw ConfigException("A2G_OPENAI_BASE_URL must be https:// (the API key is sent to it); http is only accepted for localhost.")
            }
        }
    }
}
