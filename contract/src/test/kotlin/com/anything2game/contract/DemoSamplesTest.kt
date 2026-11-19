package com.anything2game.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every pre-generated demo course (samples/demo) passes the same checks as live output, and is labelled pre-generated. */
class DemoSamplesTest {
    private val dir = File(System.getProperty("contract.samples") ?: "../samples/demo")
    private val packages = dir.listFiles { f -> f.name.startsWith("package.") }.orEmpty().map { ContractJson.decodePackage(it.readText()) }.associateBy { it.packageId }

    @Test fun `demo packages are valid and labelled pre-generated`() {
        assertTrue("expected demo packages in $dir", packages.isNotEmpty())
        packages.values.forEach { p ->
            assertEquals(p.packageId, emptyList<Issue>(), PackageValidator.validate(p))
            assertEquals(GenerationMode.PRE_GENERATED, p.generation.mode)
        }
    }

    @Test fun `demo frames are valid for their packages`() {
        dir.listFiles { f -> f.name.startsWith("frame.") }.orEmpty().forEach { f ->
            val o = ContractJson.decodeFrame(f.readText())
            assertEquals(f.name, emptyList<Issue>(), FrameValidator.validate(o, packages.getValue(o.packageId)))
        }
    }
}
