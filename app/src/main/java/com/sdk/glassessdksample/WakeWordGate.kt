 package com.sdk.glassessdksample

object WakeWordGate {

    private const val WAKE_PHRASE = "hey imi"
    private val multiSpaceRegex = "\\s+".toRegex()
    private val prefixPunctuation = setOf(',', '.', '!', '?', ':', ';', '-', '_')

    data class ParsedInput(
        val hasWakePhrase: Boolean,
        val cleanedInput: String,
        val normalizedInput: String
    )

    fun parse(rawInput: String?): ParsedInput {
        if (rawInput.isNullOrBlank()) {
            return ParsedInput(
                hasWakePhrase = false,
                cleanedInput = "",
                normalizedInput = ""
            )
        }

        val normalized = rawInput
            .lowercase()
            .trim()
            .replace(multiSpaceRegex, " ")

        if (!normalized.startsWith(WAKE_PHRASE)) {
            return ParsedInput(
                hasWakePhrase = false,
                cleanedInput = "",
                normalizedInput = normalized
            )
        }

        val withoutPrefix = normalized
            .removePrefix(WAKE_PHRASE)
            .trimStart { ch -> ch.isWhitespace() || prefixPunctuation.contains(ch) }

        return ParsedInput(
            hasWakePhrase = true,
            cleanedInput = withoutPrefix,
            normalizedInput = normalized
        )
    }
}