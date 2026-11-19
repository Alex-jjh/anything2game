plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Tests read the canonical examples straight from contract/examples.
tasks.test {
    systemProperty("contract.examples", layout.projectDirectory.dir("examples").asFile.absolutePath)
    // Pre-generated demo courses ship in the app too, so they are held to the same validators.
    systemProperty("contract.samples", rootProject.layout.projectDirectory.dir("samples/demo").asFile.absolutePath)
}
