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

    @Test fun unavailableBridgeFallsBackOnlyWithMatchingDirectCredentials() {
        val pc = AgentExecutionTarget.PC_REMOTE_SESSION
        val phone = AgentExecutionTarget.PHONE_LOCAL_RESOURCES
        val particle = "particle-ai/particle-1:free"
        assertEquals(phone, phoneFallbackTarget(pc, true, particle, "", "router"))
        assertEquals(phone, phoneFallbackTarget(pc, true, "models/gemini-2.5-pro", "gemini", ""))
        assertEquals(pc, phoneFallbackTarget(pc, false, particle, "", "router"))
        assertEquals(pc, phoneFallbackTarget(pc, true, particle, "gemini", " "))
        assertEquals(pc, phoneFallbackTarget(pc, true, "gemini-4-argon-high", "gemini", "router"))
    }
}
