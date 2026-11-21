package com.google.antigravity.googlepro

import com.google.antigravity.model.AntigravityModelOption

/** Подпись раздела: модели идут по подписке, а не по ключу API. */
private const val PRO_BADGE = "подписка аккаунта"

/**
 * Состав моделей для подписочного маршрута.
 *
 * Показывается **только** каталог подписки с сервера (`fetchAvailableModels`):
 * статических догадок больше нет — они и выдавали устаревший список без Gemini 4.
 * Нет ответа сервера — нет и списка; пустой список честнее неверного.
 */
internal fun googleProModelOptions(
    serverModels: List<String> = emptyList(),
    plan: String = ""
): List<AntigravityModelOption> {
    val badge = if (plan.isBlank()) PRO_BADGE else "$plan • $PRO_BADGE"
    return serverModels
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "auto" && !it.startsWith(GoogleProConfig.MODEL_PREFIX) }
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
        "xhigh" -> "(XHigh)"
        "sonar" -> "Sonar"
        "tiered" -> "(Tiered)"
        "fast" -> "(Fast)"
        "preview" -> "preview"
        else -> token.replaceFirstChar { it.uppercase() }
    }
}
