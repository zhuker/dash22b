package com.example.dash22b.obd

/**
 * Evaluates SSM parameter conversion expressions.
 * Supports simple arithmetic expressions like "x/4", "x-40", "(x-128)/2", etc.
 *
 * Uses regex pattern matching to parse common expression formats without requiring
 * a full expression parser library.
 */
object SsmExpressionEvaluator {
    /**
     * Evaluate an expression with the given raw value.
     *
     * @param expression The conversion expression (e.g., "x/4", "x-40", "(x-128)/2")
     * @param x The raw integer value from the ECU
     * @return The converted floating-point value
     */
    fun evaluate(expression: String, x: Int): Float {
        val xf = x.toFloat()
        val trimmed = expression.trim()

        // Pattern: x*N/M or x/N*M (chained multiply and divide)
        // Examples: "x*100/255", "x*8/100"
        val chainedMultDiv = Regex("""x\s*([*/])\s*(\d+(?:\.\d+)?)\s*([*/])\s*(\d+(?:\.\d+)?)""")
            .matchEntire(trimmed)
        if (chainedMultDiv != null) {
            val op1 = chainedMultDiv.groups[1]!!.value
            val val1 = chainedMultDiv.groups[2]!!.value.toFloat()
            val op2 = chainedMultDiv.groups[3]!!.value
            val val2 = chainedMultDiv.groups[4]!!.value.toFloat()

            val temp = if (op1 == "*") xf * val1 else xf / val1
            return if (op2 == "*") temp * val2 else temp / val2
        }

        // Pattern: (x±N)/M or (x±N)*M
        // Examples: "(x-128)/2", "(x+10)*3"
        val parenthesized = Regex("""\(x\s*([+\-])\s*(\d+(?:\.\d+)?)\)\s*([*/])\s*(\d+(?:\.\d+)?)""")
            .matchEntire(trimmed)
        if (parenthesized != null) {
            val op1 = parenthesized.groups[1]!!.value
            val val1 = parenthesized.groups[2]!!.value.toFloat()
            val op2 = parenthesized.groups[3]!!.value
            val val2 = parenthesized.groups[4]!!.value.toFloat()

            val temp = if (op1 == "+") xf + val1 else xf - val1
            return if (op2 == "*") temp * val2 else temp / val2
        }

        // Pattern: x/N or x*N
        // Examples: "x/4", "x*100"
        val multDiv = Regex("""x\s*([*/])\s*(\d+(?:\.\d+)?)""")
            .matchEntire(trimmed)
        if (multDiv != null) {
            val op = multDiv.groups[1]!!.value
            val value = multDiv.groups[2]!!.value.toFloat()
            return if (op == "*") xf * value else xf / value
        }

        // Pattern: x±N
        // Examples: "x-40", "x+10"
        val addSub = Regex("""x\s*([+\-])\s*(\d+(?:\.\d+)?)""")
            .matchEntire(trimmed)
        if (addSub != null) {
            val op = addSub.groups[1]!!.value
            val value = addSub.groups[2]!!.value.toFloat()
            return if (op == "+") xf + value else xf - value
        }

        // Pattern: x (identity)
        if (trimmed == "x") {
            return xf
        }

        // Fallback: return identity and log warning
        timber.log.Timber.w("Unknown expression format: '$expression', using identity")
        return xf
    }
}
