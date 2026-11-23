package com.anything2game.app.journeys

import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import com.anything2game.app.AndroidAppContainer
import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GenerationJourneyTest : JourneyTest() {
    private fun useServer(server: HttpServer): AndroidAppContainer {
        val container = AndroidAppContainer(app, "http://127.0.0.1:${server.address.port}")
        val field = app.javaClass.getDeclaredField("dependencies\$delegate")
        field.isAccessible = true
        field.set(app, lazy { container })
        return container
    }

    @Test fun rejected_concept_keeps_the_topic_and_the_supported_offline_game_remains_playable() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val json = ContractJson.json
        server.createContext("/") { exchange ->
            val auth = exchange.requestURI.path == "/v1/installs"
            val bytes = if (auth) json.encodeToString(InstallResponse("install", "token")).toByteArray()
                else json.encodeToString(ErrorBody(ApiError("UNSUPPORTED_TOPIC", "outside reviewed topics", false))).toByteArray()
            exchange.sendResponseHeaders(if (auth) 200 else 422, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            useServer(server)
            launch(); chooseGuest(); tap(rule.onNodeWithTag("tab_PLAYGROUND"))
            rule.onNodeWithTag("concept_input").performTextInput("orbital dynamics")
            tap(rule.onNodeWithTag("generate_game"))
            waitForText(str(com.anything2game.app.R.string.job_unsupported))
            rule.onNodeWithTag("concept_input").assertTextContains("orbital dynamics")
            assertTrue(runBlocking { deps.generation!!.list().single().admissionError?.code == "UNSUPPORTED_TOPIC" })
            tap(rule.onNodeWithTag("game_partition"))
            waitForTag("cut_8")
        } finally { server.stop(0) }
    }

    @Test fun a_saved_job_stops_polling_in_background_and_opens_a_verified_generated_board_on_return() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val ready = AtomicBoolean(false)
        val polls = AtomicInteger()
        val json = ContractJson.json
        val game = ReviewedGames.partition("en").copy(gameId = "generated-lockers", title = "Generated lockers")
        val version = ContentVersion(game.gameId, game.gameVersion)
        val pack = PackCodec.build(game, PackOrigin.SERVER).let { it.copy(manifest = it.manifest.copy(modelId = "fixture-game", fixture = true)) }
        val bytes = PackCodec.toBytes(pack)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val response = when (path) {
                "/v1/installs" -> json.encodeToString(InstallResponse("install", "token")).toByteArray()
                "/v1/generation-jobs" -> {
                    val request = json.decodeFromString<GenerationRequest>(exchange.requestBody.bufferedReader().readText())
                    require(request.input == GenerationInput.ConceptGame("binary search", "en"))
                    json.encodeToString(GenerationStatus("job", CompileJobState.QUEUED, GenerationStage.KNOWLEDGE)).toByteArray()
                }
                "/v1/generation-jobs/job" -> {
                    polls.incrementAndGet()
                    val status = if (ready.get()) GenerationStatus("job", CompileJobState.READY, GenerationStage.PACKAGE,
                        GeneratedContent(version, ContentKind.GAME), modelId = "fixture-game", fixture = true)
                        else GenerationStatus("job", CompileJobState.GENERATING, GenerationStage.EXPERIENCE)
                    json.encodeToString(status).toByteArray()
                }
                "/v1/content/generated-lockers/versions/1" -> bytes
                else -> { exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext }
            }
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val container = useServer(server)
            launch(); chooseGuest()
            tap(rule.onNodeWithTag("tab_PLAYGROUND"))
            rule.onNodeWithTag("concept_input").performTextInput("binary search")
            tap(rule.onNodeWithTag("generate_game"))
            waitFor("real design stage") { textShown(str(com.anything2game.app.R.string.job_experience)) }
            scenario!!.moveToState(Lifecycle.State.CREATED)
            val before = polls.get()
            ready.set(true)
            Thread.sleep(1600)
            assertEquals(before, polls.get())
            assertNull(runBlocking { container.content.game(version) })
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            waitForTag("game_board"); waitForText("Generated lockers")
            tap(rule.onNodeWithTag("cut_8")); tap(rule.onNodeWithTag("split_submit"))
            waitForText("8 doors eliminated")
            systemBack()
            waitForText("Test provider · fixture-game")
            assertTrue(runBlocking { container.generation.list().single().installed })
        } finally { server.stop(0) }
    }
}
