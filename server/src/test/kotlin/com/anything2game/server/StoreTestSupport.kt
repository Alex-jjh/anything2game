package com.anything2game.server

import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentKind
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.PageText
import com.anything2game.server.config.DbConfig
import com.anything2game.server.config.Secret
import com.anything2game.server.ingest.Chunker
import com.anything2game.server.store.FileStore
import com.anything2game.server.store.JobRecord
import com.anything2game.server.store.PostgresStore
import com.anything2game.server.store.Store
import com.anything2game.server.store.StoredDocument
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager
import java.util.UUID

/**
 * One PostgreSQL container for the whole test run (Testcontainers; the Ryuk sidecar removes it even if the JVM is killed).
 * When Docker is not available every Postgres test is skipped through JUnit's Assume, not failed.
 */
object PostgresContainer {
    private val container: PostgreSQLContainer<*>? by lazy {
        try {
            if (!DockerClientFactory.instance().isDockerAvailable) null
            else PostgreSQLContainer("postgres:16-alpine").also { c ->
                c.start()
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { c.stop() } })
            }
        } catch (e: Throwable) {
            System.err.println("Postgres tests skipped: ${e.javaClass.simpleName}")
            null
        }
    }

    fun requireDocker(): PostgreSQLContainer<*> {
        val c = container
        Assume.assumeTrue("Docker is not available: Postgres tests skipped", c != null)
        return c!!
    }

    /** A fresh, empty database in the shared container. */
    fun newDatabase(): TestDatabase {
        val c = requireDocker()
        val name = "t_" + UUID.randomUUID().toString().replace("-", "")
        admin(c).use { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
        return TestDatabase(c, name)
    }

    private fun admin(c: PostgreSQLContainer<*>) = DriverManager.getConnection("jdbc:postgresql://${c.host}:${c.getMappedPort(5432)}/postgres", c.username, c.password)

    class TestDatabase(private val c: PostgreSQLContainer<*>, val name: String) : AutoCloseable {
        val url = "jdbc:postgresql://${c.host}:${c.getMappedPort(5432)}/$name"
        fun config(poolSize: Int = 6) = DbConfig(url, c.username, Secret(c.password), poolSize)
        override fun close() {
            runCatching { admin(c).use { it.createStatement().use { s -> s.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } } }
        }
    }
}

/** A storage backend for the shared repository tests. [open] returns a store instance on it; for Postgres every call is a new "server instance". */
interface TestBackend : AutoCloseable {
    fun open(): Store
}

class FileBackend : TestBackend {
    private val dir = File(System.getProperty("server.test.tmp") ?: "build/test-data").resolve("fb-" + UUID.randomUUID()).also { it.mkdirs() }
    // FileStore supports one instance per directory (it is for local development), so every "instance" is the same object.
    private val store = FileStore(dir.toPath())
    override fun open(): Store = store
    override fun close() { dir.deleteRecursively() }
}

class PostgresBackend : TestBackend {
    private val db = PostgresContainer.newDatabase()
    private val stores = mutableListOf<Store>()
    override fun open(): Store = PostgresStore.connect(db.config()).also { stores += it }
    override fun close() {
        stores.forEach { runCatching { it.close() } }
        db.close()
    }
}

/** Base of the tests that run against FileStore and PostgresStore alike. */
abstract class StoreBackendTest {
    protected abstract fun newBackend(): TestBackend
    private lateinit var backend: TestBackend
    protected lateinit var store: Store

    @Before fun openBackend() {
        backend = newBackend()
        store = backend.open()
    }

    @After fun closeBackend() {
        if (::backend.isInitialized) backend.close()
    }

    /** Another server instance on the same storage. */
    protected fun anotherInstance(): Store = backend.open()

    protected fun newDocument(owner: String = "install:ins_a", id: String = "doc_" + UUID.randomUUID().toString().take(8)): StoredDocument {
        val pages = pagesOf(Examples.retrieval).mapIndexed { i, t -> PageText(i, t, "${i + 1}") }
        return StoredDocument(id, owner, "Doc", DocumentKind.TEXT, Chunker.documentVersion(pages), pages.size, 1L, Chunker.chunk(pages))
            .also { store.saveDocument(it) }
    }

    protected fun newJob(doc: StoredDocument, clientRequestId: String = UUID.randomUUID().toString(), createdAt: Long = System.nanoTime(), id: String = "job_" + UUID.randomUUID().toString().take(8)) =
        JobRecord(
            jobId = id, ownerId = doc.ownerId, documentId = doc.documentId, documentVersion = doc.documentVersion, options = CompileOptions(),
            clientRequestId = clientRequestId, packageId = "pkg_" + id.removePrefix("job_"), createdAt = createdAt, updatedAt = createdAt,
        )

    protected fun frameFor(packageId: String): FrameOverlay =
        ContractJson.decodeFrame(File(System.getProperty("contract.examples"), "frame.v1.retrieval.case.json").readText()).copy(packageId = packageId)
}
