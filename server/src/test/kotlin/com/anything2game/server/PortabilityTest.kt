package com.anything2game.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.anything2game.contract.ErrorCodes
import com.anything2game.server.api.module
import com.anything2game.server.config.ConfigException
import com.anything2game.server.config.LogFormat
import com.anything2game.server.config.Provider
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.config.StoreKind
import com.anything2game.server.ingest.IngestException
import com.anything2game.server.ingest.PdfTextExtractor
import com.anything2game.server.llm.LlmFactory
import com.anything2game.server.llm.Tier
import com.anything2game.server.store.Store
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong

class PortabilityTest {

    // ---- per-tier model configuration -------------------------------------------------------------------------------

    @Test fun `per-tier variables win and the shared ones are the fallback`() {
        val c = ServerConfig.fromEnv(
            mapOf(
                // compile: DeepSeek on its own variables; frame: Qwen; flash: nothing set per tier, old style
                "A2G_COMPILE_PROVIDER" to "openai-compatible", "A2G_COMPILE_BASE_URL" to "https://api.deepseek.com/",
                "A2G_COMPILE_MODEL" to "deepseek-reasoner", "A2G_COMPILE_API_KEY" to "k-compile-SECRET",
                "A2G_FRAME_PROVIDER" to "openai", "A2G_FRAME_BASE_URL" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "A2G_FRAME_MODEL" to "qwen-plus", "A2G_FRAME_API_KEY" to "k-frame-SECRET",
                "A2G_LLM_FLASH" to "openai", "A2G_OPENAI_API_KEY" to "k-shared-SECRET", "A2G_OPENAI_MODEL_FLASH" to "flash-model",
            ),
        )
        val compile = c.tier(Tier.COMPILE)
        assertEquals(Provider.OPENAI, compile.provider)
        assertEquals("https://api.deepseek.com", compile.baseUrl)
        assertEquals("deepseek-reasoner", compile.model)
        assertEquals("k-compile-SECRET", compile.apiKey!!.reveal())
        val frame = c.tier(Tier.FRAME)
        assertEquals("qwen-plus", frame.model)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", frame.baseUrl)
        val flash = c.tier(Tier.FLASH)
        assertEquals("falls back to A2G_OPENAI_BASE_URL default", "https://api.deepseek.com", flash.baseUrl)
        assertEquals("flash-model", flash.model)
        assertEquals("k-shared-SECRET", flash.apiKey!!.reveal())
        assertFalse(c.toString().contains("SECRET"))
        // the router builds the clients with those models, without touching the network
        val router = LlmFactory.router(c)
        assertEquals("deepseek-reasoner", router.client(Tier.COMPILE).modelId)
        assertEquals("qwen-plus", router.client(Tier.FRAME).modelId)
        assertEquals("flash-model", router.client(Tier.FLASH).modelId)
    }

    @Test fun `the old shared variables alone still configure every tier`() {
        val c = ServerConfig.fromEnv(
            mapOf(
                "A2G_LLM_COMPILE" to "openai", "A2G_LLM_FRAME" to "openai", "A2G_LLM_FLASH" to "anthropic", "A2G_OPENAI_API_KEY" to "k", "ANTHROPIC_API_KEY" to "a",
                "A2G_OPENAI_MODEL_STRONG" to "strong", "A2G_OPENAI_MODEL_FLASH" to "cheap", "A2G_ANTHROPIC_MODEL" to "claude-x", "A2G_OPENAI_BASE_URL" to "https://llm.example.com/v1",
            ),
        )
        assertEquals("strong", c.tier(Tier.COMPILE).model)
        assertEquals("strong", c.tier(Tier.FRAME).model)
        assertEquals("https://llm.example.com/v1", c.tier(Tier.FRAME).baseUrl)
        assertEquals("claude-x", c.tier(Tier.FLASH).model)
        assertNull(c.tier(Tier.FLASH).baseUrl)
        assertEquals("fake-llm", ServerConfig.fromEnv(emptyMap()).tier(Tier.COMPILE).model)
    }

    @Test fun `A2G_TIER_PROVIDER beats A2G_LLM_TIER, 'openai' is an alias and a bad value is named`() {
        val c = ServerConfig.fromEnv(mapOf("A2G_LLM_FLASH" to "anthropic", "A2G_FLASH_PROVIDER" to "fake", "A2G_LLM_FRAME" to "openai-compatible", "A2G_FRAME_API_KEY" to "k"))
        assertEquals(Provider.FAKE, c.flashProvider)
        assertEquals(Provider.OPENAI, c.frameProvider)
        try { ServerConfig.fromEnv(mapOf("A2G_FLASH_PROVIDER" to "gemini")); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("A2G_FLASH_PROVIDER")) }
    }

    @Test fun `a per-tier key and a per-tier https rule are enforced`() {
        fun msg(env: Map<String, String>) = try { ServerConfig.fromEnv(env); fail("expected ConfigException"); "" } catch (e: ConfigException) { e.message!! }
        assertTrue(msg(mapOf("A2G_COMPILE_PROVIDER" to "openai")).contains("A2G_COMPILE_API_KEY"))
        assertTrue(msg(mapOf("A2G_COMPILE_PROVIDER" to "anthropic")).contains("ANTHROPIC_API_KEY"))
        assertTrue(msg(mapOf("A2G_FLASH_PROVIDER" to "openai", "A2G_FLASH_API_KEY" to "k", "A2G_FLASH_BASE_URL" to "http://api.example.com")).contains("https"))
        // Gemini's OpenAI-compatible endpoint, with its trailing slash
        val g = ServerConfig.fromEnv(
            mapOf("A2G_FLASH_PROVIDER" to "openai", "A2G_FLASH_API_KEY" to "k", "A2G_FLASH_BASE_URL" to "https://generativelanguage.googleapis.com/v1beta/openai/", "A2G_FLASH_MODEL" to "m"),
        )
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", g.tier(Tier.FLASH).baseUrl)
    }

    // ---- port, store, worker settings -------------------------------------------------------------------------------

    @Test fun `PORT is honoured when A2G_PORT is unset, and A2G_PORT wins`() {
        assertEquals(8080, ServerConfig.fromEnv(emptyMap()).port)
        assertEquals(9091, ServerConfig.fromEnv(mapOf("PORT" to "9091")).port)
        assertEquals(7000, ServerConfig.fromEnv(mapOf("PORT" to "9091", "A2G_PORT" to "7000")).port)
        try { ServerConfig.fromEnv(mapOf("PORT" to "abc")); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("PORT")) }
    }

    @Test fun `the store defaults to file and postgres needs its settings`() {
        val d = ServerConfig.fromEnv(emptyMap())
        assertEquals(StoreKind.FILE, d.storeKind)
        assertNull(d.db)
        try { ServerConfig.fromEnv(mapOf("A2G_STORE" to "mysql")); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("A2G_STORE")) }
        try { ServerConfig.fromEnv(mapOf("A2G_STORE" to "postgres")); fail() } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("A2G_DB_URL") && e.message!!.contains("A2G_DB_PASSWORD"))
        }
        try { ServerConfig.fromEnv(mapOf("A2G_STORE" to "postgres", "A2G_DB_URL" to "jdbc:postgresql://h/db", "A2G_DB_USER" to "u")); fail() } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("missing: A2G_DB_PASSWORD"))
        }
        try { ServerConfig.fromEnv(mapOf("A2G_STORE" to "postgres", "A2G_DB_URL" to "mysql://h/db", "A2G_DB_USER" to "u", "A2G_DB_PASSWORD" to "p")); fail() } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("jdbc:postgresql:"))
        }
        val pg = ServerConfig.fromEnv(
            mapOf("A2G_STORE" to "postgres", "A2G_DB_URL" to "jdbc:postgresql://h:5432/a2g", "A2G_DB_USER" to "u", "A2G_DB_PASSWORD" to "pw-SECRET", "A2G_DB_POOL_SIZE" to "7"),
        )
        assertEquals(StoreKind.POSTGRES, pg.storeKind)
        assertEquals(7, pg.db!!.poolSize)
        assertEquals("pw-SECRET", pg.db!!.password.reveal())
        assertFalse(pg.toString().contains("SECRET"))
    }

    @Test fun `worker, pdf and log settings have defaults and bounds`() {
        val c = ServerConfig.fromEnv(emptyMap())
        assertEquals(20, c.pdfTimeoutSeconds)
        assertEquals(LogFormat.TEXT, c.logFormat)
        assertEquals(30_000, c.workerLeaseMillis)
        assertEquals(1_000, c.workerPollMillis)
        val s = ServerConfig.fromEnv(mapOf("A2G_PDF_TIMEOUT_SECONDS" to "5", "A2G_LOG_FORMAT" to "JSON", "A2G_WORKER_LEASE_SECONDS" to "60"))
        assertEquals(5, s.pdfTimeoutSeconds)
        assertEquals(LogFormat.JSON, s.logFormat)
        assertEquals(60_000, s.workerLeaseMillis)
        try { ServerConfig.fromEnv(mapOf("A2G_LOG_FORMAT" to "xml")); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("A2G_LOG_FORMAT")) }
    }

    // ---- readiness and request ids ----------------------------------------------------------------------------------

    @Test fun `ready answers 200 when the store works and 503 when it does not, health stays up`() {
        TestEnv().use { env ->
            env.run {
                val r = client.get("/v1/ready")
                assertEquals(HttpStatusCode.OK, r.status)
                assertTrue(r.bodyAsText().contains("ready"))
            }
        }
        val broken = TestEnv()
        broken.use { env ->
            val failing = object : Store by env.store { override fun ping() = throw java.sql.SQLException("down") }
            val ctx = AppContext(env.config, failing, com.anything2game.server.llm.LlmRouter(env.llm, env.llm, env.llm), jwtAuthenticator = null)
            io.ktor.server.testing.testApplication {
                application { module(ctx) }
                assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/ready").status)
                assertTrue(client.get("/v1/ready").bodyAsText().contains("INTERNAL_ERROR"))
                assertEquals(HttpStatusCode.OK, client.get("/v1/health").status)
            }
        }
    }

    @Test fun `every call gets a request id, a harmless incoming id is kept and a hostile one is replaced`() = TestEnv().use { env ->
        env.run {
            val generated = client.get("/v1/health").headers["X-Request-Id"]
            assertTrue(!generated.isNullOrBlank())
            assertEquals("lb-123.abc_9", client.get("/v1/health") { header("X-Request-Id", "lb-123.abc_9") }.headers["X-Request-Id"])
            val hostile = client.get("/v1/health") { header("X-Request-Id", "bad id with spaces") }
            assertEquals(HttpStatusCode.OK, hostile.status)
            assertNotEquals("bad id with spaces", hostile.headers["X-Request-Id"])
            assertNotEquals(generated, client.get("/v1/health").headers["X-Request-Id"])
        }
    }

    @Test fun `the access log has method, path and status but never the query, a token or document text`() = TestEnv().use { env ->
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            env.run {
                val api = api()
                val (_, token) = api.install()
                val secretText = "Zebra-quartz-needle is the unique phrase of this document."
                val doc = api.document(token, listOf(secretText))
                client.get("/v1/compile-jobs/job_x?secret=QUERYSECRET") { header(HttpHeaders.Authorization, "Bearer $token") }
                api.ask(token, "pkg_none", "L1U1", "What is the QUESTIONSECRET?")
                assertTrue(doc.documentId.isNotEmpty())
                val lines = appender.list.map { it.formattedMessage + " " + it.mdcPropertyMap }
                assertTrue(lines.any { it.contains("GET /v1/compile-jobs/job_x -> 401") || it.contains("GET /v1/compile-jobs/job_x -> 404") })
                val all = lines.joinToString("\n")
                assertFalse(all.contains("QUERYSECRET"))
                assertFalse(all.contains(token))
                assertFalse(all.contains("Zebra-quartz-needle"))
                assertFalse(all.contains("QUESTIONSECRET"))
                assertTrue("the request id is in the log context", appender.list.any { it.mdcPropertyMap.containsKey("requestId") })
            }
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test fun `with A2G_LOG_FORMAT=json logback writes one JSON object per line with the request id`() {
        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        val context = LoggerContext().also { it.mdcAdapter = ch.qos.logback.classic.util.LogbackMDCAdapter() }
        System.setProperty("A2G_LOG_FORMAT", "json")
        try {
            System.setOut(PrintStream(captured, true, "UTF-8"))
            val configurator = JoranConfigurator().also { it.context = context }
            configurator.doConfigure(javaClass.getResource("/logback.xml"))
            context.mdcAdapter.put("requestId", "req-777")
            context.getLogger("test").info("hello {}", "world")
            context.stop()
        } finally {
            System.clearProperty("A2G_LOG_FORMAT")
            System.setOut(originalOut)
        }
        val line = captured.toString("UTF-8").lines().firstOrNull { it.contains("hello") } ?: fail("no JSON line; captured: ${captured.toString("UTF-8")} status: ${context.statusManager.copyOfStatusList.joinToString(" | ")}").let { "" }
        val obj = Json.parseToJsonElement(line).jsonObject
        assertEquals("INFO", obj["level"]!!.jsonPrimitive.content)
        assertTrue(obj["formattedMessage"]!!.jsonPrimitive.content == "hello world")
        assertEquals("req-777", obj["mdc"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
    }

    // ---- PDF time limit -----------------------------------------------------------------------------------------------

    @Test fun `a PDF that takes longer than the limit is refused with a contract error code`() {
        val now = AtomicLong(0)
        // every look at the clock costs one second, so a 2-page file blows a 1.5 s budget at the second page
        val slow = PdfTextExtractor(80, timeoutMillis = 1_500, nanoClock = { now.addAndGet(1_000_000_000L) })
        try { slow.extract(makePdf(listOf("one", "two", "three"))); fail() } catch (e: IngestException) {
            assertEquals(ErrorCodes.UNSUPPORTED_TYPE, e.code)
            assertTrue(e.message!!.contains("too long"))
        }
        // a normal budget is fine
        assertEquals(3, PdfTextExtractor(80, timeoutMillis = 20_000).extract(makePdf(listOf("one", "two", "three"))).size)
    }

    @Test fun `the upload route answers 415 UNSUPPORTED_TYPE for a PDF that took too long`() {
        val now = AtomicLong(0)
        TestEnv().use { env ->
            val slow = PdfTextExtractor(80, timeoutMillis = 1_500, nanoClock = { now.addAndGet(1_000_000_000L) })
            val ctx = AppContext(env.config, env.store, com.anything2game.server.llm.LlmRouter(env.llm, env.llm, env.llm), jwtAuthenticator = null, pdf = slow)
            io.ktor.server.testing.testApplication {
                application { module(ctx) }
                val api = Api(client)
                val (_, token) = api.install()
                val r = api.uploadPdf(token, makePdf(listOf("a", "b", "c")))
                assertEquals(HttpStatusCode.UnsupportedMediaType, r.status)
                assertEquals(ErrorCodes.UNSUPPORTED_TYPE, api.error(r).code)
            }
        }
    }
}
