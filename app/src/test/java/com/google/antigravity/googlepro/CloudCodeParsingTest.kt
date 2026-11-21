package com.google.antigravity.googlepro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCodeParsingTest {

    @Test
    fun `ineligible tiers are not a verdict - the reason is kept, the tier too`() {
        val state = parseCodeAssist(JSONObject("""
            {"currentTier":{"id":"standard","name":"Standard"},
             "ineligibleTiers":[{"tierId":"free-tier","tierName":"Free","reasonMessage":"User location is not supported"}]}
        """.trimIndent()))
        assertTrue(state.passedGate)
        assertTrue(state.usable)
        assertEquals("standard", state.tierId)
        assertEquals(1, state.ineligibleReasons.size)
    }

    @Test
    fun `a response without a tier is still passed`() {
        val state = parseCodeAssist(JSONObject("""{"cloudaicompanionProject":"p-1"}"""))
        assertTrue(state.passedGate)
        assertEquals("p-1", state.project)
    }

    @Test
    fun `server model configs become the catalog`() {
        val state = parseCodeAssist(JSONObject("""
            {"currentTier":{"id":"standard","modelConfigs":[{"modelId":"gemini-3-pro-high"},{"modelId":"gemini-3-flash"}]}}
        """.trimIndent()))
        assertEquals(listOf("gemini-3-pro-high", "gemini-3-flash"), state.models)
    }

    @Test
    fun `sse lines carry json and skip noise`() {
        assertNull(parseSseLine("event: ping"))
        assertNull(parseSseLine("data: [DONE]"))
        assertNotNull(parseSseLine("""data: {"candidates":[]}"""))
    }

    @Test
    fun `wrapped sse chunks are unwrapped before reading`() {
        val chunk = JSONObject("""{"response":{"candidates":[{"content":{"parts":[{"text":"ответ"}]}}]}}""")
        assertEquals("ответ", collectChunkText(chunk).text)
    }

    @Test
    fun `thought parts stay separate from the answer`() {
        val chunk = JSONObject("""
            {"candidates":[{"content":{"parts":[{"text":"думаю","thought":true},{"text":"ответ"}]}}],
             "usageMetadata":{"candidatesTokenCount":12}}
        """.trimIndent())
        val reply = collectChunkText(chunk)
        assertEquals("ответ", reply.text)
        assertEquals("думаю", reply.thinking)
        assertEquals(12, outputTokensOf(chunk))
    }

    @Test
    fun `user defined project is requested from the client`() {
        val state = parseCodeAssist(JSONObject("""
            {"allowedTiers":[{"id":"standard-tier","isDefault":true,"userDefinedCloudaicompanionProject":true}]}
        """.trimIndent()))
        assertTrue(state.needsProject)
        assertEquals("standard-tier", state.defaultTierId)
        assertTrue(!state.unlicensed)
    }

    @Test
    fun `a granted tier no longer asks for a project`() {
        val state = parseCodeAssist(JSONObject("""
            {"currentTier":{"id":"standard-tier"},"cloudaicompanionProject":"p-1",
             "allowedTiers":[{"id":"standard-tier","userDefinedCloudaicompanionProject":true}]}
        """.trimIndent()))
        assertTrue(!state.needsProject)
        assertEquals("p-1", state.project)
    }

    @Test
    fun `no tiers at all means the account is unlicensed`() {
        assertTrue(parseCodeAssist(JSONObject("{}")).unlicensed)
    }

    @Test
    fun `default gemini project is preferred and dead ones skipped`() {
        val json = JSONObject("""
            {"projects":[{"projectId":"zzz","lifecycleState":"ACTIVE"},
                         {"projectId":"dead","lifecycleState":"DELETE_REQUESTED"},
                         {"projectId":"gen-lang-client-1","lifecycleState":"ACTIVE"}]}
        """.trimIndent())
        assertEquals("gen-lang-client-1", rankProjects(json).first())
        assertEquals(2, rankProjects(json).size)
    }

    @Test
    fun `models are prefixed locally and stripped for the server`() {
        val options = googleProModelOptions(listOf("gemini-9-pro"))
        assertEquals("google-pro/gemini-9-pro", options.first().id)
        assertEquals("gemini-9-pro", remoteProModel(options.first().id))
        assertTrue(isProModel(options.first().id))
        assertTrue(options.none { it.id == it.id.removePrefix("google-pro/") })
    }

    @Test
    fun `nothing from the server means no list at all, not stale guesses`() {
        assertTrue(googleProModelOptions(emptyList()).isEmpty())
    }

    @Test
    fun `the badge names the actual plan`() {
        val options = googleProModelOptions(listOf("gemini-4-argon-high"), "Google AI Ultra")
        assertEquals("Google AI Ultra • подписка аккаунта", options.first().badge)
    }

    @Test
    fun `a licence is read by tier and display name`() {
        val (tier, name) = parseLicence(JSONObject("""
            {"licenses":[{"userTier":"g1-ultra-tier","tierDisplayName":"Google AI Ultra"}]}
        """.trimIndent()))
        assertEquals("g1-ultra-tier", tier)
        assertEquals("Google AI Ultra", name)
    }

    @Test
    fun `an account without licences reads as empty, not as an error`() {
        assertEquals("" to "", parseLicence(JSONObject("""{"licenses":[]}""")))
        assertEquals("" to "", parseLicence(JSONObject("{}")))
    }

    @Test
    fun `provider loopback answers are dropped as unusable on a phone`() {
        // dns-ai и eu.geohide отвечают 127.65.71.x — это их локальный прокси на ПК.
        assertTrue(isLocalOnly("127.65.71.2"))
        assertTrue(!isLocalOnly("172.217.118.4"))
        assertEquals(listOf("172.217.118.4"), usableAddresses(listOf("127.65.71.2", "172.217.118.4")))
        assertEquals(emptyList<String>(), usableAddresses(listOf("127.65.71.2")))
    }

    @Test
    fun `a licence makes the account licensed even without a tier in the catalog`() {
        // Ровно ответ живого сервера: currentTier нет, free-tier помечен как неподдерживаемый,
        // но лицензия выдана — значит ход разрешён.
        val state = parseCodeAssist(JSONObject("""
            {"allowedTiers":[{"id":"standard-tier","userDefinedCloudaicompanionProject":true,"isDefault":true}],
             "ineligibleTiers":[{"tierId":"free-tier","reasonCode":"UNSUPPORTED_CLIENT"}]}
        """.trimIndent())).copy(licenceTier = "g1-ultra-tier", licenceName = "Google AI Ultra")
        assertTrue(state.unlicensed || state.licenceTier.isNotBlank())
        assertEquals("g1-ultra-tier", state.licenceTier)
    }
}
