package com.anything2game.app.journeys

import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import com.anything2game.app.AndroidAppContainer
import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import com.anything2game.app.R
import com.anything2game.app.data.profile.LocalProfile
import java.util.concurrent.CopyOnWriteArrayList
import com.sun.net.httpserver.HttpServer
import com.anything2game.app.awaitIo
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

    @Test fun partial_route_regeneration_lists_current_local_exclusions_before_syncing_and_sending_a_new_request() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val json = ContractJson.json
        var remote = Space("remote", "Seminar", 1)
        val requests = CopyOnWriteArrayList<GenerationRequest>()
        server.createContext("/") { x ->
            val body = when (x.requestURI.path) {
                "/v1/installs" -> json.encodeToString(InstallResponse("install", "token"))
                "/v1/spaces", "/v1/spaces/remote" -> json.encodeToString(remote)
                "/v1/spaces/remote/members" -> {
                    val member = json.decodeFromString<SpaceMemberRequest>(x.requestBody.bufferedReader().readText()).member
                    remote = remote.copy(revision = remote.revision + 1, members = remote.members + member)
                    json.encodeToString(remote)
                }
                "/v1/generation-jobs" -> {
                    requests += json.decodeFromString<GenerationRequest>(x.requestBody.bufferedReader().readText())
                    json.encodeToString(GenerationStatus("job-${requests.size}", if (requests.size == 1) CompileJobState.PARTIAL else CompileJobState.CANCELLED))
                }
                else -> { x.sendResponseHeaders(404, -1); x.close(); return@createContext }
            }.toByteArray()
            x.sendResponseHeaders(200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val container = useServer(server)
            val (space, ticket, local) = awaitIo {
                container.profiles.choose(LocalProfile.GUEST)
                val space = container.spaces.create("Seminar")
                val request = container.generation.enqueue(container.spaces.pathRequest(space.spaceId, "en"))
                assertEquals(CompileJobState.PARTIAL, container.generation.refresh(request.id).state)
                val local = container.materials.materials().first { it.member is SpaceMember.LocalPackRef }
                container.spaces.add(space.spaceId, local.member)
                Triple(space, request, local)
            }
            launch()
            val tag = "open_space_${space.spaceId}"
            waitFor("Seminar Space") { runCatching { rule.onNodeWithTag("home_SPACES").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false) }
            tap(rule.onNodeWithTag(tag))
            val retry = "retry_generation_${ticket.id}"
            waitForTag(retry)
            tap(rule.onNodeWithTag(retry))
            rule.onNode(hasAnyAncestor(isDialog()) and hasText(local.title, substring = true)).assertExists()
            assertEquals(1, requests.size)
            tap(rule.onNode(hasAnyAncestor(isDialog()) and hasText(str(R.string.import_cancel))))
            assertEquals(1, requests.size)
            tap(rule.onNodeWithTag(retry))
            tap(rule.onNode(hasAnyAncestor(isDialog()) and hasText(str(R.string.spaces_generate)) and hasClickAction()))
            waitFor("second request dispatched") { requests.size == 2 }
            assertNotEquals(requests.first().clientRequestId, requests.last().clientRequestId)
            assertEquals(GenerationInput.SpacePath("remote", 2, "en"), requests.last().input)
        } finally { server.stop(0) }
    }

    @Test fun recovered_terminal_poll_removes_connection_error_and_keeps_cancelled_status() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val json = ContractJson.json
        val restored = AtomicBoolean(false)
        server.createContext("/") { x ->
            var code = 200
            val body = when (x.requestURI.path) {
                "/v1/installs" -> json.encodeToString(InstallResponse("install", "token"))
                "/v1/generation-jobs" -> json.encodeToString(GenerationStatus("job", CompileJobState.QUEUED))
                "/v1/generation-jobs/job" -> if (restored.get()) json.encodeToString(GenerationStatus("job", CompileJobState.CANCELLED))
                    else { code = 503; json.encodeToString(ErrorBody(ApiError("OFFLINE", "temporary outage", true))) }
                else -> { x.sendResponseHeaders(404, -1); x.close(); return@createContext }
            }.toByteArray()
            x.sendResponseHeaders(code, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val container = useServer(server)
            awaitIo {
                container.profiles.choose(LocalProfile.GUEST)
                container.generation.enqueue(GenerationInput.ConceptGame("binary search", "en"))
            }
            launch(); waitForTag("tab_PLAYGROUND"); openGenerationTools()
            val interrupted = hasText(str(R.string.job_offline), substring = true)
            waitFor("temporary polling failure shown") { exists(interrupted) }
            restored.set(true)
            waitForText(str(R.string.job_cancelled))
            rule.onNode(interrupted).assertDoesNotExist()
        } finally { server.stop(0) }
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
            launch(); chooseGuest(); openGenerationTools()
            rule.onNodeWithTag("concept_input").performTextInput("orbital dynamics")
            tap(rule.onNodeWithTag("generate_game"))
            waitForText(str(com.anything2game.app.R.string.job_unsupported))
            rule.onNodeWithTag("concept_input").assertTextContains("orbital dynamics")
            assertTrue(awaitIo { deps.generation!!.list().single().admissionError?.code == "UNSUPPORTED_TOPIC" })
            tap(rule.onNodeWithTag("tab_PLAYGROUND"))
            waitForTag("game_partition"); openGame(rule.onNodeWithTag("game_partition"))
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
            openGenerationTools()
            rule.onNodeWithTag("concept_input").performTextInput("binary search")
            tap(rule.onNodeWithTag("generate_game"))
            waitFor("real design stage") { textShown(str(com.anything2game.app.R.string.job_experience)) }
            scenario!!.moveToState(Lifecycle.State.CREATED)
            val before = polls.get()
            ready.set(true)
            Thread.sleep(1600)
            assertEquals(before, polls.get())
            assertNull(awaitIo { container.content.game(version) })
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            waitForGame(); waitForText("Generated lockers")
            tap(rule.onNodeWithTag("cut_8")); tap(rule.onNodeWithTag("split_submit"))
            waitForText("Crossed off 8 lockers")
            systemBack()
            tap(rule.onNodeWithTag("tab_PLAYGROUND"))
            waitForText(str(R.string.games_fixture, "fixture-game"))
            assertTrue(awaitIo { container.generation.list().single().installed })
            val checkpoint = awaitIo { requireNotNull(container.learningProgress.checkpoint(version)) }
            org.robolectric.RuntimeEnvironment.setQualifiers("+zh")
            scenario!!.recreate()
            waitForTag("tab_PLAYGROUND"); tap(rule.onNodeWithTag("tab_PLAYGROUND"))
            for (tag in listOf("resume_game", "game_${version.contentId}_${version.contentVersion}")) {
                waitForTag(tag)
                tap(rule.onNodeWithTag(tag))
                waitForText("Generated lockers")
                waitForGame()
                assertEquals(checkpoint, awaitIo { container.learningProgress.checkpoint(version) })
                systemBack()
            }
        } finally { server.stop(0) }
    }

    @Test fun an_imported_archive_claiming_a_server_model_displays_import_provenance() {
        val pkg = ReviewedGames.partition("en").copy(gameId = "independent-game", gameVersion = "1", title = "Imported lockers")
        val pack = PackCodec.build(pkg, PackOrigin.SERVER)
        awaitIo {
            deps.content!!.install(PackCodec.toBytes(pack.copy(manifest = pack.manifest.copy(modelId = "fixture-game", fixture = true))).inputStream())
        }
        launch(); chooseGuest(); tap(rule.onNodeWithTag("tab_PLAYGROUND"))
        waitForText("Imported lockers")
        rule.onAllNodesWithText(str(R.string.games_fixture, "fixture-game")).assertCountEquals(0)
        rule.onNodeWithText(str(R.string.games_imported)).assertExists()
    }
}
