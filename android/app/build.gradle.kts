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
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Bundled samples ARE the contract examples: one source of truth, validated by :contract tests.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("contract/examples"))
    // Pre-generated demo courses (validated by :contract DemoSamplesTest). Labelled "pre-generated" in the UI.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("samples/demo"))
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

tasks.withType<Test>().configureEach {
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
