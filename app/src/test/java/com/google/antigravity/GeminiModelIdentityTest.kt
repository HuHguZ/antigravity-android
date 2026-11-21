package com.google.antigravity

import com.google.antigravity.model.mapToDirectGeminiModelId
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelIdentityTest {
    @Test fun bridgeModelIsNeverSilentlyRenamedToAnotherGeneration() {
        assertEquals("gemini-4-argon-high", mapToDirectGeminiModelId("gemini-4-argon-high"))
        assertEquals("gemini-4-argon-low", mapToDirectGeminiModelId("gemini-4-argon-low"))
    }

    @Test fun directApiModelKeepsItsExactIdentity() {
        assertEquals("gemini-4-argon", mapToDirectGeminiModelId("models/gemini-4-argon"))
        assertEquals("gemini-4-hydrogen", mapToDirectGeminiModelId("gemini-4-hydrogen"))
        assertEquals("google/gemini-2.5-pro", mapToDirectGeminiModelId("google/gemini-2.5-pro"))
    }

    @Test fun oldDirectApiSelectionsUseTheDocumentedReplacements() {
        assertEquals("gemini-3.8-flash", mapToDirectGeminiModelId("models/gemini-2.5-pro"))
        assertEquals("gemini-3.5-flash-lite", mapToDirectGeminiModelId("gemini-2.5-flash"))
    }
}
