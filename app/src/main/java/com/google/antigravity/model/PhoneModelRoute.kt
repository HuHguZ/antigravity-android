package com.google.antigravity.model

enum class PhoneModelRoute { PC_CLI, GEMINI_API, OPENROUTER_API }

private val CLI_GEMINI_MODEL = Regex("gemini-(4-.+|3\\.8-flash)-(xhigh|high|medium|low|fast)")

/** Inference location is independent of the phone tool executor. */
fun phoneModelRoute(modelId: String): PhoneModelRoute = when {
    CLI_GEMINI_MODEL.matches(modelId) || modelId == "gemini-pro-orchestrator" -> PhoneModelRoute.PC_CLI
    modelId.contains("/") && !modelId.startsWith("models/") -> PhoneModelRoute.OPENROUTER_API
    else -> PhoneModelRoute.GEMINI_API
}
