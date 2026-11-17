package com.anything2game.engine

import com.anything2game.contract.ContractJson
import com.anything2game.contract.LearningPackage
import java.io.File

object Fixtures {
    private val dir = File(System.getProperty("contract.examples") ?: "contract/examples")
    val pkg: LearningPackage by lazy { ContractJson.decodePackage(File(dir, "package.v1.example.json").readText()) }
}
