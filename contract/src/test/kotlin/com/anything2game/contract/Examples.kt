package com.anything2game.contract

import java.io.File

/** Loads canonical files from contract/examples (path injected by Gradle). */
object Examples {
    private val dir = File(System.getProperty("contract.examples") ?: "examples")
    fun text(name: String): String = File(dir, name).readText()
    fun packageFiles(): List<File> = dir.listFiles { f -> f.name.startsWith("package.") && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    fun frameFiles(): List<File> = dir.listFiles { f -> f.name.startsWith("frame.") && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    val packageV1: LearningPackage get() = ContractJson.decodePackage(text("package.v1.example.json"))
}
