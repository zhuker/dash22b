package com.example.dash22b.obd

/**
 * Evaluates SSM parameter conversion expressions. Supports simple arithmetic expressions like
 * "x/4", "x-40", "(x-128)/2", etc.
 *
 * Uses regex pattern matching to parse common expression formats without requiring a full
 * expression parser library.
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

        // Pattern: N/(M±x) or N*(M±x)
        // Examples: "14.7/(1+x)", "1*(128+x)"
        val constOpParenthesized = Regex(
            """((?:\d+\.\d*|\.\d+|\d+))\s*([*/])\s*\(\s*((?:\d+\.\d*|\.\d+|\d+))\s*([+\-])\s*x\s*\)"""
        ).matchEntire(trimmed)
        if (constOpParenthesized != null) {
            val val1 = constOpParenthesized.groups[1]!!.value.toFloat()
            val op1 = constOpParenthesized.groups[2]!!.value
            val val2 = constOpParenthesized.groups[3]!!.value.toFloat()
            val op2 = constOpParenthesized.groups[4]!!.value

            val inner = if (op2 == "+") val2 + xf else val2 - xf
            return if (op1 == "*") val1 * inner else val1 / inner
        }

        // Pattern: (x±N)*M/P or (x±N)/M*P (chained parenthesized)
        // Examples: "(x-128)*100/128", "(x+40)/2*1.5"
        val parenthesizedChained = Regex(
            """\(x\s*([+\-])\s*((?:\d+\.\d*|\.\d+|\d+))\)\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))"""
        ).matchEntire(trimmed)
        if (parenthesizedChained != null) {
            val op1 = parenthesizedChained.groups[1]!!.value
            val val1 = parenthesizedChained.groups[2]!!.value.toFloat()
            val op2 = parenthesizedChained.groups[3]!!.value
            val val2 = parenthesizedChained.groups[4]!!.value.toFloat()
            val op3 = parenthesizedChained.groups[5]!!.value
            val val3 = parenthesizedChained.groups[6]!!.value.toFloat()

            val temp1 = if (op1 == "+") xf + val1 else xf - val1
            val temp2 = if (op2 == "*") temp1 * val2 else temp1 / val2
            return if (op3 == "*") temp2 * val3 else temp2 / val3
        }

        // Pattern: (x*N)±M or (x/N)±M
        // Examples: "(x*.078125)-5", "(x/4)+10"
        val parenthesizedStart = Regex(
            """\(x\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))\)\s*([+\-])\s*((?:\d+\.\d*|\.\d+|\d+))"""
        ).matchEntire(trimmed)
        if (parenthesizedStart != null) {
            val op1 = parenthesizedStart.groups[1]!!.value
            val val1 = parenthesizedStart.groups[2]!!.value.toFloat()
            val op2 = parenthesizedStart.groups[3]!!.value
            val val2 = parenthesizedStart.groups[4]!!.value.toFloat()

            val temp = if (op1 == "*") xf * val1 else xf / val1
            return if (op2 == "+") temp + val2 else temp - val2
        }

        // Pattern: x*N±M or x/N±M
        // Examples: "x*25-3200", "x/4+10"
        val multDivAddSub = Regex(
            """x\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))\s*([+\-])\s*((?:\d+\.\d*|\.\d+|\d+))"""
        ).matchEntire(trimmed)
        if (multDivAddSub != null) {
            val op1 = multDivAddSub.groups[1]!!.value
            val val1 = multDivAddSub.groups[2]!!.value.toFloat()
            val op2 = multDivAddSub.groups[3]!!.value
            val val2 = multDivAddSub.groups[4]!!.value.toFloat()

            val temp = if (op1 == "*") xf * val1 else xf / val1
            return if (op2 == "+") temp + val2 else temp - val2
        }

        // Pattern: x*N/M or x/N*M (chained multiply and divide)
        // Examples: "x*100/255", "x*8/100"
        val chainedMultDiv = Regex(
            """x\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))"""
        ).matchEntire(trimmed)
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
        val parenthesized = Regex(
            """\(x\s*([+\-])\s*((?:\d+\.\d*|\.\d+|\d+))\)\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))"""
        ).matchEntire(trimmed)
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
        val multDiv = Regex("""x\s*([*/])\s*((?:\d+\.\d*|\.\d+|\d+))""").matchEntire(trimmed)
        if (multDiv != null) {
            val op = multDiv.groups[1]!!.value
            val value = multDiv.groups[2]!!.value.toFloat()
            return if (op == "*") xf * value else xf / value
        }

        // Pattern: x±N
        // Examples: "x-40", "x+10"
        val addSub = Regex("""x\s*([+\-])\s*((?:\d+\.\d*|\.\d+|\d+))""").matchEntire(trimmed)
        if (addSub != null) {
            val op = addSub.groups[1]!!.value
            val value = addSub.groups[2]!!.value.toFloat()
            return if (op == "+") xf + value else xf - value
        }

        // Pattern: N/x or N*x
        // Examples: "1/x"
        val constMultDivX = Regex("""((?:\d+\.\d*|\.\d+|\d+))\s*([*/])\s*x""").matchEntire(trimmed)
        if (constMultDivX != null) {
            val value = constMultDivX.groups[1]!!.value.toFloat()
            val op = constMultDivX.groups[2]!!.value
            return if (op == "*") value * xf else value / xf
        }

        // Pattern: N±x
        // Examples: "0-x", "100+x"
        val constPlusMinusX =
            Regex("""((?:\d+\.\d*|\.\d+|\d+))\s*([+\-])\s*x""").matchEntire(trimmed)
        if (constPlusMinusX != null) {
            val value = constPlusMinusX.groups[1]!!.value.toFloat()
            val op = constPlusMinusX.groups[2]!!.value
            return if (op == "+") value + xf else value - xf
        }

        // Pattern: bit:N
        // Examples: "bit:6"
        val bitCheck = Regex("""bit:(\d+)""").matchEntire(trimmed)
        if (bitCheck != null) {
            val bit = bitCheck.groups[1]!!.value.toInt()
            return if ((x and (1 shl bit)) != 0) 1f else 0f
        }

        // Pattern: x (identity)
        if (trimmed == "x") {
            return xf
        }

//         Fallback: return identity and log warning
//        throw UnsupportedOperationException("Unknown expression format: '$expression'")
        timber.log.Timber.w("Unknown expression format: '$expression', using identity")
        return xf
    }
}
