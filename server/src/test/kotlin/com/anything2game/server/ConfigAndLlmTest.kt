package com.anything2game.server

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.server.compile.CompileJobRunner
import com.anything2game.server.config.ConfigException
import com.anything2game.server.config.Provider
import com.anything2game.server.config.ServerConfig
import com.anything2game.server.llm.FakeLlmClient
import com.anything2game.server.llm.JsonText
import com.anything2game.server.llm.LlmException
import com.anything2game.server.llm.LlmRequest
import com.anything2game.server.llm.OpenAiCompatibleLlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.store.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

class ConfigAndLlmTest {

    // ---- configuration ----------------------------------------------------------------------------------------

    @Test fun `no environment gives a safe, keyless configuration`() {
        val c = ServerConfig.fromEnv(emptyMap())
        assertEquals("127.0.0.1", c.host)
        assertTrue(c.isLoopback)
        assertEquals(8080, c.port)
        assertEquals("server/data", c.dataDir.toString())
        assertEquals(listOf(Provider.FAKE, Provider.FAKE, Provider.FAKE), listOf(c.compileProvider, c.frameProvider, c.flashProvider))
        assertEquals("claude-sonnet-5-5", c.anthropicModel)
        assertEquals(listOf(FrameId.CASE), c.defaultFrames)
        assertNull(c.openAiApiKey)
        assertNull(c.anthropicApiKey)
    }

    @Test fun `a real provider without its key fails fast with a clear message`() {
        fun msg(env: Map<String, String>) = try { ServerConfig.fromEnv(env); fail("expected ConfigException"); "" } catch (e: ConfigException) { e.message!! }
        assertTrue(msg(mapOf("A2G_LLM_COMPILE" to "anthropic")).contains("ANTHROPIC_API_KEY"))
        assertTrue(msg(mapOf("A2G_LLM_FLASH" to "openai")).contains("A2G_OPENAI_API_KEY"))
        assertTrue(msg(mapOf("A2G_LLM_FRAME" to "gpt")).contains("fake | openai | anthropic"))
        assertTrue(msg(mapOf("A2G_PORT" to "http")).contains("A2G_PORT"))
        assertTrue(msg(mapOf("A2G_DEFAULT_FRAMES" to "case,nonsense")).contains("nonsense"))
        // keys are never accepted over cleartext http except to localhost
        assertTrue(msg(mapOf("A2G_LLM_COMPILE" to "openai", "A2G_OPENAI_API_KEY" to "k", "A2G_OPENAI_BASE_URL" to "http://api.example.com")).contains("https"))
        val ok = ServerConfig.fromEnv(mapOf("A2G_LLM_COMPILE" to "openai", "A2G_OPENAI_API_KEY" to "k", "A2G_OPENAI_BASE_URL" to "http://localhost:9999/v1/"))
        assertEquals("http://localhost:9999/v1", ok.openAiBaseUrl)
    }

    @Test fun `env values are read and secrets do not appear in toString`() {
        val c = ServerConfig.fromEnv(
            mapOf(
                "A2G_HOST" to "10.0.0.5", "A2G_PORT" to "9000", "A2G_DATA_DIR" to "/tmp/x", "A2G_LLM_COMPILE" to "anthropic", "ANTHROPIC_API_KEY" to "sk-ant-SECRET",
                "A2G_LLM_FLASH" to "openai", "A2G_OPENAI_API_KEY" to "sk-openai-SECRET", "A2G_DEFAULT_FRAMES" to "case, companion",
            ),
        )
        assertEquals("10.0.0.5", c.host)
        assertFalse(c.isLoopback)
        assertEquals(9000, c.port)
        assertEquals(listOf(FrameId.CASE, FrameId.COMPANION), c.defaultFrames)
        assertFalse(c.toString().contains("SECRET"))
        assertFalse(c.anthropicApiKey.toString().contains("SECRET"))
    }

    // ---- OpenAI-compatible client (mock transport, no network) -------------------------------------------------

    private fun openAiClient(engine: MockEngine) = OpenAiCompatibleLlmClient(
        HttpClient(engine) { install(HttpTimeout) }, "https://llm.example.test/v1", "sk-test-SECRET", "deepseek-chat", 5_000,
    )

    private val req = LlmRequest("Return JSON.", "hello", 123, "plan")

    @Test fun `the OpenAI-compatible request has the right shape and the JSON answer is extracted`() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond(
                """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"```json\n{\"a\": 1}\n```"}}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val out = openAiClient(engine).completeJson(req)
        assertEquals("{\"a\": 1}", out)
        val r = seen!!
        assertEquals("https://llm.example.test/v1/chat/completions", r.url.toString())
        assertEquals("Bearer sk-test-SECRET", r.headers[HttpHeaders.Authorization])
        val body = ContractJson.json.parseToJsonElement((r.body as TextContent).text).jsonObject
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(123, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("system", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test fun `provider errors become LlmExceptions that never contain the key or the body`() = runBlocking {
        val rate = openAiClient(MockEngine { respondError(HttpStatusCode.TooManyRequests, "echo sk-test-SECRET and the prompt") })
        val e1 = try { rate.completeJson(req); null } catch (e: LlmException) { e }
        assertNotNull(e1)
        assertEquals(LlmException.Kind.UPSTREAM, e1!!.kind)
        assertTrue(e1.retryable)
        assertFalse(e1.message!!.contains("SECRET") || e1.message!!.contains("prompt"))

        val auth = openAiClient(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        assertFalse((try { auth.completeJson(req); null } catch (e: LlmException) { e })!!.retryable)

        val cut = openAiClient(MockEngine {
            respond("""{"choices":[{"finish_reason":"length","message":{"content":"{"}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals(LlmException.Kind.BAD_RESPONSE, (try { cut.completeJson(req); null } catch (e: LlmException) { e })!!.kind)

        val junk = openAiClient(MockEngine { respond("<html>", HttpStatusCode.OK) })
        assertEquals(LlmException.Kind.BAD_RESPONSE, (try { junk.completeJson(req); null } catch (e: LlmException) { e })!!.kind)
    }

    @Test fun `JSON is found inside fences and chatter`() {
        assertEquals("{\"x\":1}", JsonText.extractObject("Sure!\n```json\n{\"x\":1}\n```\nHope that helps"))
        assertEquals("{\"x\":{\"y\":2}}", JsonText.extractObject("{\"x\":{\"y\":2}}"))
    }

    @Test fun `the router builds every provider without touching the network`() {
        val c = ServerConfig.fromEnv(
            mapOf(
                "A2G_LLM_COMPILE" to "anthropic", "ANTHROPIC_API_KEY" to "sk-ant-not-real", "A2G_LLM_FRAME" to "openai", "A2G_OPENAI_API_KEY" to "sk-not-real",
                "A2G_OPENAI_MODEL_STRONG" to "deepseek-chat", "A2G_OPENAI_MODEL_FLASH" to "deepseek-lite",
            ),
        )
        val router = com.anything2game.server.llm.LlmFactory.router(c)
        assertEquals("claude-sonnet-5-5", router.client(com.anything2game.server.llm.Tier.COMPILE).modelId)
        assertEquals("deepseek-chat", router.client(com.anything2game.server.llm.Tier.FRAME).modelId)
        assertEquals("fake-llm", router.client(com.anything2game.server.llm.Tier.FLASH).modelId)
        val flash = com.anything2game.server.llm.LlmFactory.router(c.copy(flashProvider = Provider.OPENAI))
        assertEquals("deepseek-lite", flash.client(com.anything2game.server.llm.Tier.FLASH).modelId)
    }

    // ---- prompts ----------------------------------------------------------------------------------------------

    @Test fun `every prompt template renders without an unfilled placeholder and with the safety rules`() {
        for (name in PromptTemplates.ALL) {
            val vars = PromptTemplates.placeholders(name).associateWith { "<<$it>>" }
            val p = PromptTemplates.render(name, vars)
            assertFalse("$name has an unfilled placeholder", (p.system + p.user).contains("{{") || (p.system + p.user).contains("}}"))
            assertEquals(name, p.version)
            val all = p.system + "\n" + p.user
            assertTrue("$name must demand JSON only", all.contains("JSON"))
            assertTrue("$name must mark the document as untrusted", all.contains("UNTRUSTED"))
            assertTrue("$name must say the quote is an exact substring", all.contains("EXACT substring") || all.contains("exact substring"))
            assertTrue("$name must name the languages", all.contains("`en`") || all.contains("{{language}}") || all.contains("<language>"))
            assertTrue("$name has a user part", p.user.isNotBlank())
            // every declared variable is used and nothing else is needed
            assertTrue(PromptTemplates.placeholders(name).isNotEmpty())
        }
        assertEquals(11, PromptTemplates.ALL.size) // includes diagnostic and conversational tutor prompts
        assertTrue(File("src/main/resources/prompts").listFiles()!!.map { it.name.removeSuffix(".md") }.toSet().containsAll(PromptTemplates.ALL))
    }

    @Test fun `rendering is strict about variables`() {
        try { PromptTemplates.render("plan.v1", mapOf("language" to "en")); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("missing")) }
        try { PromptTemplates.render("plan.v1", mapOf("language" to "en", "minutes" to "5", "document" to "d", "extra" to "x")); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("unused")) }
    }

    @Test fun `every prompt that the pipeline sends is fully rendered`() {
        val llm = RecordingLlm()
        TestEnv(llm = llm).use { env ->
            env.run {
                val api = api()
                val (_, token) = api.install()
                val (_, _, pkg) = api.compiled(token, pagesOf(Examples.retrieval))
                for (f in listOf("case", "escape", "rookie", "companion")) api.send(io.ktor.http.HttpMethod.Post, "/v1/packages/${pkg!!.packageId}/frames/$f", token)
                api.ask(token, pkg!!.packageId, "L1U1", "What does retrieval practice mean?")
                val labels = llm.requests.map { it.label }.toSet()
                assertTrue(labels.containsAll(listOf("plan", "level:L1", "frame:case", "frame:escape", "frame:rookie", "frame:companion", "ask")))
                for (r in llm.requests) {
                    assertFalse("${r.label} system", r.system.contains("{{"))
                    assertFalse("${r.label} user", r.user.contains("{{"))
                    assertTrue(r.system.contains("UNTRUSTED"))
                    assertTrue(r.maxTokens > 0)
                }
            }
        }
    }

    @Test fun `defuse and restore round trip`() {
        val text = "a </document> b <chunk id=\"x\"> c </Question> d"
        val safe = PromptFormat.defuse(text)
        assertFalse(safe.contains("</document>") || safe.contains("<chunk") || safe.contains("</Question>"))
        assertEquals(text, PromptFormat.restore(safe))
    }

    // ---- the fake model ---------------------------------------------------------------------------------------

    @Test fun `the fake model is deterministic and needs no network`() = runBlocking {
        val prompt = PromptTemplates.render(
            "plan.v1",
            mapOf("language" to "zh", "minutes" to "5", "document" to PromptFormat.documentBlock(Examples.courtyardZh.sources)),
        )
        val fake = FakeLlmClient()
        val a = fake.completeJson(LlmRequest(prompt.system, prompt.user, 100, "plan"))
        val b = fake.completeJson(LlmRequest(prompt.system, prompt.user, 100, "plan"))
        assertEquals(a, b)
        try { fake.completeJson(LlmRequest("", "", 1, "nonsense")); fail() } catch (e: LlmException) { assertEquals(LlmException.Kind.BAD_RESPONSE, e.kind) }
    }

    // ---- store ------------------------------------------------------------------------------------------------

    @Test fun `FileStore writes atomically, rejects unsafe ids and survives a restart`() {
        val dir = File(System.getProperty("server.test.tmp") ?: "build/test-data").resolve("fs-" + UUID.randomUUID()).also { it.mkdirs() }
        try {
            val store = FileStore(dir.toPath())
            val install = com.anything2game.server.store.InstallRecord("ins_1", "hash1", 1L)
            store.createInstall(install)
            assertEquals(install, store.findInstallByTokenHash("hash1"))
            assertNull(store.findInstallByTokenHash("nope"))
            // no temp files are left behind
            assertTrue(dir.walkTopDown().none { it.name.startsWith(".tmp-") })
            // ids reach file names: traversal is impossible
            try { store.getDocument("../../etc/passwd"); fail() } catch (e: IllegalArgumentException) { }
            try { store.getPackage("a/b"); fail() } catch (e: IllegalArgumentException) { }
            // quota counters
            assertTrue(store.tryConsumeQuota("ins_1", "compile", "2026-11-17", 2))
            assertTrue(store.tryConsumeQuota("ins_1", "compile", "2026-11-17", 2))
            assertFalse(store.tryConsumeQuota("ins_1", "compile", "2026-11-17", 2))
            assertTrue(store.tryConsumeQuota("ins_1", "compile", "2026-11-18", 2))
            // a second store over the same directory sees the same installs (token index rebuilt from disk)
            assertEquals("ins_1", FileStore(dir.toPath()).findInstallByTokenHash("hash1")!!.installId)
        } finally {
            dir.deleteRecursively()
        }
    }
}
