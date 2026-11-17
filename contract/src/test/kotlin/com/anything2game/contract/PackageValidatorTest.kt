package com.anything2game.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageValidatorTest {

    private val pkg = Examples.packageV1

    private fun issues(p: LearningPackage) = PackageValidator.validate(p)

    private fun LearningPackage.mapUnit(index: Int, f: (LearningUnit) -> LearningUnit) =
        copy(levels = levels.mapIndexed { li, l -> if (li != 0) l else l.copy(units = l.units.mapIndexed { ui, u -> if (ui == index) f(u) else u }) })

    @Test fun `every example package is valid`() {
        val files = Examples.packageFiles()
        assertTrue("expected example packages", files.size >= 2)
        files.forEach { f -> assertEquals(f.name, emptyList<Issue>(), issues(ContractJson.decodePackage(f.readText()))) }
    }

    @Test fun `canonical example is valid`() {
        assertEquals(emptyList<Issue>(), issues(pkg))
        assertEquals(listOf(Mechanic.SWIPE, Mechanic.SORT, Mechanic.DEDUCE), pkg.levels[0].units.map { it.mechanic })
    }

    @Test fun `round trip keeps the package identical`() {
        assertEquals(pkg, ContractJson.decodePackage(ContractJson.encodePackage(pkg)))
    }

    @Test fun `unknown fields are ignored for forward compatibility`() {
        val text = Examples.text("package.v1.example.json").replaceFirst("{", "{\"futureField\": 1,")
        assertEquals(pkg, ContractJson.decodePackage(text))
    }

    @Test fun `unsupported schema is rejected`() {
        assertTrue(issues(pkg.copy(schema = "a2g.package/v0")).any { it.path == "schema" })
    }

    @Test fun `citation to a missing chunk is rejected`() {
        val bad = pkg.mapUnit(2) { u -> u.copy(item = (u.item as DeduceItem).copy(sourceRefs = listOf(SourceRef("nope", "x")))) }
        assertTrue(issues(bad).any { "unknown chunk 'nope'" in it.message })
    }

    @Test fun `quote that is not in the chunk is rejected`() {
        val bad = pkg.mapUnit(2) { u -> u.copy(item = (u.item as DeduceItem).copy(sourceRefs = listOf(SourceRef("c1", "Students loved it.")))) }
        assertTrue(issues(bad).any { "quote not found" in it.message })
    }

    @Test fun `quote matching ignores line breaks from PDF extraction`() {
        val ok = pkg.mapUnit(2) { u -> u.copy(item = (u.item as DeduceItem).copy(sourceRefs = listOf(SourceRef("c1", "One week later\n  the recall group")))) }
        assertEquals(emptyList<Issue>(), issues(ok))
    }

    @Test fun `deduce needs exactly one correct option`() {
        val bad = pkg.mapUnit(2) { u ->
            val d = u.item as DeduceItem
            u.copy(item = d.copy(options = d.options.map { it.copy(correct = true) }))
        }
        assertTrue(issues(bad).any { "exactly one option" in it.message })
    }

    @Test fun `sort card must point at an existing bin`() {
        val bad = pkg.mapUnit(1) { u ->
            val s = u.item as SortItem
            u.copy(item = s.copy(cards = s.cards.mapIndexed { i, c -> if (i == 0) c.copy(binId = "ghost") else c }))
        }
        assertTrue(issues(bad).any { "unknown bin 'ghost'" in it.message })
    }

    @Test fun `item type must match the unit mechanic`() {
        val bad = pkg.mapUnit(0) { u -> u.copy(mechanic = Mechanic.SORT) }
        assertTrue(issues(bad).any { "does not match" in it.message })
    }

    @Test fun `duplicate unit ids are rejected`() {
        val bad = pkg.mapUnit(1) { u -> u.copy(unitId = "L1U1") }
        assertTrue(issues(bad).any { "duplicate unitId" in it.message })
    }

    @Test fun `readValidPackage refuses invalid packages`() {
        val text = ContractJson.encodePackage(pkg.copy(schema = "x"))
        assertTrue(ContractJson.readValidPackage(text).isFailure)
        assertTrue(ContractJson.readValidPackage(Examples.text("package.v1.example.json")).isSuccess)
    }
}
