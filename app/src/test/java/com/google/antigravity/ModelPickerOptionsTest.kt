package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelPickerOptionsTest {
    @Test fun preservesSavedOpenRouterSelectionMissingFromBridgeCatalog() {
        val id = "openrouter/vendor/custom-model"
        val result = modelPickerOptions(AVAILABLE_AGY_MODELS, id, "custom-model")
        assertEquals(id, result.first().id)
        assertEquals("custom-model", result.first().displayName)
        assertEquals(1, result.count { it.id == id })
    }

    @Test fun removesDuplicateCatalogIdsWithoutReplacingTheirMetadata() {
        val first = AntigravityModelOption("same", "First", "Description")
        val duplicate = first.copy(displayName = "Duplicate")
        val result = modelPickerOptions(listOf(first, duplicate), "same", "First")
        assertEquals(listOf(first), result.filter { it.id == "same" })
    }

    @Test fun oldBridgeCatalogKeepsCurrentDirectGeminiOptions() {
        val old = AntigravityModelOption("gemini-2.5-pro", "Old Pro", "")
        val bridge = AntigravityModelOption("gemini-4-argon-high", "Bridge Argon", "")
        val ids = modelPickerOptions(listOf(bridge, old), bridge.id, bridge.displayName).map { it.id }.toSet()
        assertFalse(old.id in ids)
        assertTrue(ids.containsAll(listOf(bridge.id, "gemini-4-hydrogen", "gemini-4-neon", "gemini-4-argon",
            "gemini-4-carbon", "gemini-3.8-flash", "gemini-3.5-flash-lite")))
    }

    @Test fun unavailableCatalogFallsBackWithoutHidingActualSavedSelection() {
        val result = modelPickerOptions(emptyList(), "custom", "Custom")
        assertEquals("custom", result.first().id)
        assertTrue(result.containsAll(AVAILABLE_AGY_MODELS))
    }
}
