package com.google.antigravity.model

private val markdownFence = Regex("(?m)^ {0,3}(`{3,}|~{3,})")

internal data class PhoneActionBlock(val range: IntRange, val json: String)

/** Only top-level phone_action fences are executable. Backticks in JSON strings are file contents. */
internal fun findPhoneActionBlocks(text: String): List<PhoneActionBlock> {
    val blocks = mutableListOf<PhoneActionBlock>()
    var cursor = 0
    while (cursor < text.length) {
        val opening = markdownFence.find(text, cursor) ?: break
        val fence = opening.groupValues[1]
        val infoStart = opening.range.last + 1
        val language = "phone_action"
        val jsonStart = infoStart + language.length
        if (fence != "```" || !text.startsWith(language, infoStart, ignoreCase = true) ||
            text.getOrNull(jsonStart)?.let { !it.isWhitespace() && it != '{' } == true) {
            val lineEnd = text.indexOf('\n', infoStart).takeIf { it >= 0 } ?: break
            val closeAt = Regex("(?m)^ {0,3}${Regex.escape(fence.first().toString())}{${fence.length},}[ \\t]*(?:\\r?$)")
                .find(text, lineEnd + 1) ?: break
            cursor = closeAt.range.last + 1
            continue
        }
        var inString = false
        var index = jsonStart
        while (index < text.length) {
            if (!inString && text.startsWith(fence, index)) break
            when (text[index]) {
                '"' -> inString = !inString
                '\\' -> if (inString) index++
            }
            index++
        }
        if (index >= text.length) break
        cursor = index + fence.length
        blocks += PhoneActionBlock(opening.range.first until cursor, text.substring(jsonStart, index).trim())
    }
    return blocks
}

internal fun removePhoneActionBlocks(text: String, blocks: List<PhoneActionBlock>): String =
    StringBuilder(text).apply {
        for (block in blocks.asReversed()) delete(block.range.first, block.range.last + 1)
    }.toString().trim()

fun processPhoneActionBlocks(text: String, execute: (String) -> Boolean): String =
    removePhoneActionBlocks(text, findPhoneActionBlocks(text).filter { execute(it.json) })
