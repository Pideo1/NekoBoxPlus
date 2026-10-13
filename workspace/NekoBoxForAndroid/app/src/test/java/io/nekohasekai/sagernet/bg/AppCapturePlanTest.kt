package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class AppCapturePlanTest {
    private fun plan(bypass: Boolean, root: Boolean = false, all: Boolean = false) = AppCapturePlan.build(
        "self", true, bypass, setOf("excluded", "rule"), setOf("rule"), setOf("adblock"),
        all, root, setOf("self", "excluded", "rule", "adblock", "other"),
    )

    @Test fun inclusionAddsRuleAndAdblockAppsAndSelf() {
        assertEquals(setOf("self", "excluded", "rule", "adblock"), plan(false).packages)
        assertEquals(false, plan(false).bypass)
    }

    @Test fun exclusionCannotExcludeAppsRequiredByRulesOrAdblock() {
        assertEquals(setOf("excluded"), plan(true).packages)
        assertEquals(true, plan(true).bypass)
    }

    @Test fun rootWorkaroundConvertsExclusionToEquivalentInclusion() {
        assertEquals(setOf("self", "rule", "adblock", "other"), plan(true, root = true).packages)
        assertEquals(false, plan(true, root = true).bypass)
    }

    @Test fun systemWideAdblockIncludesEveryInstalledApp() {
        assertEquals(setOf("self", "excluded", "rule", "adblock", "other"), plan(true, all = true).packages)
        assertEquals(false, plan(true, all = true).bypass)
    }

    @Test fun disabledPerAppSelectionCapturesWithoutAList() {
        val plan = AppCapturePlan.build("self", false, true, setOf("excluded"), emptySet(), emptySet(),
            false, false, setOf("self", "excluded"))
        assertNull(plan.bypass)
        assertTrue(plan.packages.isEmpty())
    }
}
