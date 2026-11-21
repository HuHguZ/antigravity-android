package com.google.antigravity.googlepro

import com.google.antigravity.model.AntigravityModelOption

/** Подпись раздела: модели идут по подписке, а не по ключу API. */
private const val PRO_BADGE = "подписка аккаунта"

/**
 * Состав моделей для подписочного маршрута.
 *
 * Серверный каталог (`fetchAvailableModels`) — истина. Статический список берётся
 * только пока каталога нет, и тогда он честно подписан как стартовый: имена и
 * целые семейства на стороне Google меняются чаще, чем обновляется приложение.
 */
internal fun googleProModelOptions(
    serverModels: List<String> = emptyList(),
    plan: String = ""
): List<AntigravityModelOption> {
    val fromServer = serverModels.map { it.trim() }.filter { it.isNotBlank() }
    val source = if (fromServer.isNotEmpty()) fromServer else GoogleProConfig.FALLBACK_MODELS
    val badge = if (fromServer.isNotEmpty()) (plan.ifBlank { "Google AI Pro" } + " • " + PRO_BADGE)
    else "стартовый список • обновите"
    return source
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith(GoogleProConfig.MODEL_PREFIX) }
        .distinct()
        .map { AntigravityModelOption(GoogleProConfig.MODEL_PREFIX + it, prettyProModelName(it), badge) }
}

/** `google-pro/gemini-3-pro-high` → `gemini-3-pro-high` (как ждёт CloudCode). */
internal fun remoteProModel(localId: String): String =
    localId.trim().removePrefix(GoogleProConfig.MODEL_PREFIX)

internal fun isProModel(modelId: String): Boolean =
    modelId.trim().startsWith(GoogleProConfig.MODEL_PREFIX)

/** Человекочитаемое имя из серверного идентификатора. */
internal fun prettyProModelName(remote: String): String = remote.split('-').joinToString(" ") { token ->
    when (token.lowercase()) {
        "gemini" -> "Gemini"
        "pro" -> "Pro"
        "flash" -> "Flash"
        "lite" -> "Lite"
        "high" -> "(High)"
        "medium" -> "(Medium)"
        "low" -> "(Low)"
        "sonar" -> "Sonar"
        "tiered" -> "(Tiered)"
        "fast" -> "(Fast)"
        "preview" -> "preview"
        else -> token.replaceFirstChar { it.uppercase() }
    }
}
