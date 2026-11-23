import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.anything2game.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.anything2game.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    val tutorApi = providers.gradleProperty("a2gApiBaseUrl").orElse("").get()
    require(tutorApi.isEmpty() || tutorApi.matches(Regex("https://[A-Za-z0-9._:/-]+"))) { "a2gApiBaseUrl must be an HTTPS URL" }
    defaultConfig { buildConfigField("String", "TUTOR_API", "\"$tutorApi\"") }
    buildTypes {
        getByName("debug") { buildConfigField("String", "TUTOR_API", "\"${tutorApi.ifEmpty { "http://127.0.0.1:18080" }}\"") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { generateLocaleConfig = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":android:engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

// Preset content ships as content packs (ADR-016): the same .a2gpack format the server will deliver.
// Source of truth stays as readable JSON (contract/examples, samples/demo); PackTool validates and packs it at build time.
val packTool: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
dependencies { packTool(project(":contract")) }

abstract class PackPresetsTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val inputDirs: ConfigurableFileCollection
    @get:Classpath abstract val toolClasspath: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction fun pack() {
        val out = outputDir.get().dir("packs").asFile
        out.deleteRecursively()
        exec.javaexec {
            classpath = toolClasspath
            mainClass.set("com.anything2game.contract.pack.PackToolKt")
            args(listOf(out.absolutePath) + inputDirs.files.map { it.absolutePath })
        }
    }
}

val packPresets by tasks.registering(PackPresetsTask::class) {
    inputDirs.from(rootProject.file("contract/examples"), rootProject.file("samples/demo"))
    toolClasspath.from(packTool)
    outputDir.set(layout.buildDirectory.dir("generated/presetPacks"))
}

// The demo courses' papers, bundled so Show source opens the real page with no setup (ADR-019). They are fetched from
// each layout's pdfSource at build time, checked against the sha256 the layout was measured on, and cached in the Gradle
// user home; they never enter git. Offline or a changed file: a warning, and the app falls back to "attach your PDF".
// -Pa2g.samplePdfs=false leaves them out (e.g. for any build shared outside the team: the papers are third-party).
abstract class FetchSamplePdfsTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val layouts: ConfigurableFileCollection
    @get:Input abstract val bundle: Property<Boolean>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun fetch() {
        val out = outputDir.get().dir("sample-pdfs").asFile
        out.deleteRecursively(); out.mkdirs()
        if (!bundle.get()) return
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        layouts.files.sorted().forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val l = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
            val id = l["packageId"] as String; val sha = l["pdfSha256"] as String; val url = l["pdfSource"] as? String
            val cached = File(cache, "$sha.pdf")
            if (!cached.isFile || sha256(cached.readBytes()) != sha) {
                if (url == null) { logger.warn("a2g: $id has no pdfSource; not bundled"); return@forEach }
                val bytes = try { download(url) } catch (e: Exception) { logger.warn("a2g: could not fetch $url (${e.message}); $id not bundled"); return@forEach }
                if (sha256(bytes) != sha) { logger.warn("a2g: $url is not the file $id was measured on (sha256 differs); not bundled. Re-measure with :server:presetLayout."); return@forEach }
                cached.writeBytes(bytes)
            }
            cached.copyTo(File(out, "$id.pdf"), overwrite = true)
        }
    }
    private fun download(url: String): ByteArray {
        var target = URI(url).toURL()
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "anything2game-build")
            when (c.responseCode) {
                in 300..399 -> { target = URI(url).resolve(c.getHeaderField("Location")).toURL(); c.disconnect() }
                200 -> return c.inputStream.use { it.readBytes() }
                else -> error("HTTP ${c.responseCode}")
            }
        }
        error("too many redirects")
    }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

val fetchSamplePdfs by tasks.registering(FetchSamplePdfsTask::class) {
    layouts.from(rootProject.fileTree("samples/demo") { include("layout.demo.*.json") })
    bundle.set(providers.gradleProperty("a2g.samplePdfs").map { it != "false" }.orElse(true))
    cacheDir.set(layout.dir(providers.provider { File(gradle.gradleUserHomeDir, "caches/a2g-sample-pdfs") }))
    outputDir.set(layout.buildDirectory.dir("generated/samplePdfs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packPresets, PackPresetsTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(fetchSamplePdfs, FetchSamplePdfsTask::outputDir)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("a2g.bundledPdfs", "off")
    systemProperty("contract.examples", rootProject.file("contract/examples").absolutePath)
    systemProperty("contract.samples", rootProject.file("samples/demo").absolutePath)
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
}

// ADR-013: the app NEVER talks to an AI provider. Every AI call goes phone → our server → provider.
// This check fails any build that adds an AI SDK or hard-codes an AI API host in app code.
val verifyNoDirectAiAccess by tasks.registering {
    group = "verification"
    description = "Fails if the app depends on an AI SDK or references an AI provider host (ADR-013)."
    val sources = fileTree("src/main") { include("**/*.kt", "**/*.java", "**/*.xml", "**/*.json") }
    val runtime = configurations.named("debugRuntimeClasspath")
    inputs.files(sources)
    doLast {
        val forbiddenGroups = listOf("com.anthropic", "com.openai", "com.aallam.openai", "com.google.ai.client", "com.google.genai",
            "com.google.firebase:firebase-ai", "com.google.firebase:firebase-vertexai", "com.alibaba.dashscope", "dev.langchain4j")
        val forbiddenHosts = listOf("api.anthropic.com", "api.openai.com", "api.deepseek.com", "dashscope", "generativelanguage.googleapis.com",
            "aiplatform.googleapis.com", "bedrock-runtime", "openrouter.ai")
        val deps = runtime.get().resolvedConfiguration.resolvedArtifacts.map { "${it.moduleVersion.id.group}:${it.name}" }
        val badDeps = deps.filter { d -> forbiddenGroups.any { d == it || d.startsWith("$it:") || d.substringBefore(':') == it } }
        val badFiles = sources.files.filter { f -> f.readText().let { t -> forbiddenHosts.any { it in t } } }
        check(badDeps.isEmpty() && badFiles.isEmpty()) {
            "ADR-013 violated — the app must call only our server.\n  AI SDKs: $badDeps\n  AI hosts in: ${badFiles.map { it.relativeTo(projectDir) }}"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyNoDirectAiAccess) }
