package com.google.antigravity.model

/**
 * Состав выбора модели. Подписка — отдельный мир: пока её каталог загружен,
 * показываются только модели подписки. Всё остальное (мост ПК, ключи API) —
 * модели не из подписки, и в списке им не место: устаревшие имена оттуда и
 * выглядели как «неправильные модели».
 */
fun modelPickerOptions(
    available: List<AntigravityModelOption>,
    selectedId: String,
    selectedDisplay: String,
    proModels: List<AntigravityModelOption> = emptyList()
): List<AntigravityModelOption> {
    val models = if (proModels.isNotEmpty()) {
        proModels.distinctBy { it.id }
    } else {
        (available.ifEmpty { AVAILABLE_AGY_MODELS }
            .filterNot { mapToDirectGeminiModelId(it.id) != it.id } + DIRECT_GEMINI_MODELS).distinctBy { it.id }
    }
    return if (selectedId.isNotBlank() && models.none { it.id == selectedId }) {
        listOf(AntigravityModelOption(selectedId, selectedDisplay, "Текущая модель")) + models
    } else {
        models
    }
}
