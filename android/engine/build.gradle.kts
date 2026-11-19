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
    api(project(":contract"))
    testImplementation(libs.junit)
}

tasks.test { systemProperty("contract.examples", rootProject.layout.projectDirectory.dir("contract/examples").asFile.absolutePath) }
tasks.test { systemProperty("contract.samples", rootProject.layout.projectDirectory.dir("samples/demo").asFile.absolutePath) }
