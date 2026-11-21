package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneModelRouteTest {
    @Test fun cliModelsUseBridgeInferenceWithPhoneTools() {
        listOf("gemini-4-argon-high", "gemini-4-argon-low", "gemini-3.8-flash-low", "gemini-4-hydrogen-xhigh").forEach {
            assertEquals(PhoneModelRoute.PC_CLI, phoneModelRoute(it))
        }
    }
    @Test fun apiProvidersKeepTheirRoutes() {
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("gemini-2.5-pro"))
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("models/gemini-2.5-pro"))
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("gemini-4-argon"))
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("gemini-3.8-flash"))
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("gemini-4-neon"))
        assertEquals(PhoneModelRoute.OPENROUTER_API, phoneModelRoute("google/gemini-2.5-pro"))
    }
}
