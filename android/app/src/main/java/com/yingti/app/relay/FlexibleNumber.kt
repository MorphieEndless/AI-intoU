package com.yingti.app.relay

/**
 * Tolerant numeric parsing for commands produced by LLM/MCP clients.
 *
 * MCP schemas say these fields are numbers, but some models occasionally emit
 * JSON strings such as "0.85". Accept both representations (plus a percentage
 * such as "85%") while still rejecting NaN/Infinity and unrelated text.
 */
internal object FlexibleNumber {
    fun parse(value: Any?, field: String): Double {
        val parsed = when (value) {
            is Number -> value.toDouble()
            is String -> parseString(value, field)
            else -> throw IllegalArgumentException(
                "$field must be a number or numeric string, got ${value?.javaClass?.simpleName ?: "null"}"
            )
        }
        require(parsed.isFinite()) { "$field must be finite" }
        return parsed
    }

    private fun parseString(value: String, field: String): Double {
        val text = value.trim()
        require(text.isNotEmpty()) { "$field must not be empty" }

        val isPercent = text.endsWith('%')
        val numericText = if (isPercent) text.dropLast(1).trim() else text
        val parsed = numericText.toDoubleOrNull()
            ?: throw IllegalArgumentException("$field is not numeric: $value")
        return if (isPercent) parsed / 100.0 else parsed
    }
}
