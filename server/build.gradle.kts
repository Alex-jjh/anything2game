plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

application {
    mainClass.set("com.anything2game.server.MainKt")
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":android:engine"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.pdfbox)
    implementation(libs.anthropic.java)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.nimbus.jose.jwt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
}

// Offline tooling, not part of the server: measures a preset course's page geometry on a local PDF (server/README.md,
// "Preset source layouts"). Relative paths in --args resolve from the repo root.
tasks.register<JavaExec>("presetLayout") {
    group = "tooling"
    description = "Writes samples/demo/layout.<name>.json for a preset package from a local copy of its PDF."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.anything2game.server.ingest.PresetLayoutToolKt")
    workingDir = rootProject.projectDir
}

tasks.test {
    inputs.property("localTestPostgres", providers.environmentVariable("A2G_TEST_PG_ADMIN_URL").orElse("container"))
    // Canonical contract examples are test fixtures; test data lives under build/ and is deleted by the tests.
    systemProperty("contract.examples", rootProject.layout.projectDirectory.dir("contract/examples").asFile.absolutePath)
    systemProperty("server.test.tmp", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
}
