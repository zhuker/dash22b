package com.example.dash22b.obd

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsmExpressionEvaluatorTest {

    private val delta = 0.001f

    @Test
    fun testAll() {

        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found, skipping test")
            return
        }

        val ecuInit = SsmEcuInit.createHardcoded()
        println("Listing ALL supported parameters for ROM ID: ${ecuInit.getRomId()}")

        val parameters =
                xmlFile.inputStream().use { inputStream ->
                    SsmLoggerDefinitionParser.parseParameters(inputStream, ecuInit, 1)
                }
        assertTrue(!parameters.isEmpty())
        parameters.distinctBy { it.expression }.forEach {
            print("testing ${it.name} x=10 ${it.expression} ")
            val res = SsmExpressionEvaluator.evaluate(it.expression, 10)
            println("== $res")
        }
    }

    @Test
    fun testIdentity() {
        assertEquals(10f, SsmExpressionEvaluator.evaluate("x", 10), delta)
        assertEquals(0f, SsmExpressionEvaluator.evaluate(" x ", 0), delta)
    }

    @Test
    fun testSimpleArithmetic() {
        // x / N
        assertEquals(25f, SsmExpressionEvaluator.evaluate("x/4", 100), delta)
        assertEquals(12.5f, SsmExpressionEvaluator.evaluate("x / 2", 25), delta)

        // x * N
        assertEquals(200f, SsmExpressionEvaluator.evaluate("x*2", 100), delta)
        assertEquals(1.5f, SsmExpressionEvaluator.evaluate("x * 0.5", 3), delta)

        // x - N
        assertEquals(60f, SsmExpressionEvaluator.evaluate("x-40", 100), delta)
        assertEquals(-40f, SsmExpressionEvaluator.evaluate("x - 40", 0), delta)

        // x + N
        assertEquals(110f, SsmExpressionEvaluator.evaluate("x+10", 100), delta)
        assertEquals(10.5f, SsmExpressionEvaluator.evaluate("x + 0.5", 10), delta)

        // N ± x
        assertEquals(-10f, SsmExpressionEvaluator.evaluate("0-x", 10), delta)
        assertEquals(110f, SsmExpressionEvaluator.evaluate("100 + x", 10), delta)

        // N / x
        assertEquals(0.1f, SsmExpressionEvaluator.evaluate("1/x", 10), delta)

        // bit:N
        assertEquals(
                1f,
                SsmExpressionEvaluator.evaluate("bit:3", 8),
                delta
        ) // 8 is 1000b, bit 3 is set
        assertEquals(
                0f,
                SsmExpressionEvaluator.evaluate("bit:3", 7),
                delta
        ) // 7 is 0111b, bit 3 is not set
    }

    @Test
    fun testParenthesized() {
        // (x - N) / M
        assertEquals(0f, SsmExpressionEvaluator.evaluate("(x-128)/2", 128), delta)
        assertEquals(63.5f, SsmExpressionEvaluator.evaluate("(x-128)/2", 255), delta)
        assertEquals(-64f, SsmExpressionEvaluator.evaluate("(x - 128) / 2", 0), delta)

        // (x + N) * M
        assertEquals(60f, SsmExpressionEvaluator.evaluate("(x+10)*2", 20), delta)

        // (x - N) * M / P
        assertEquals(100f, SsmExpressionEvaluator.evaluate("(x-128)*100/128", 256), delta)
        assertEquals(0f, SsmExpressionEvaluator.evaluate("(x-128)*100/128", 128), delta)
        assertEquals(-100f, SsmExpressionEvaluator.evaluate("(x-128)*100/128", 0), delta)

        // (x * N) ± M
        assertEquals(-5f, SsmExpressionEvaluator.evaluate("(x*.078125)-5", 0), delta)
        assertEquals(2.8125f, SsmExpressionEvaluator.evaluate("(x*0.078125)-5", 100), delta)
        assertEquals(15f, SsmExpressionEvaluator.evaluate("(x/4)+10", 20), delta)

        // N / (M ± x)
        assertEquals(14.7f, SsmExpressionEvaluator.evaluate("14.7/(1+x)", 0), delta)
        assertEquals(7.35f, SsmExpressionEvaluator.evaluate("14.7 / (1 + x)", 1), delta)
    }

    @Test
    fun testChained() {
        // x * N / M
        // Example: x*100/255 for percentage
        assertEquals(100f, SsmExpressionEvaluator.evaluate("x*100/255", 255), delta)
        assertEquals(0f, SsmExpressionEvaluator.evaluate("x*100/255", 0), delta)
        assertEquals(50f, SsmExpressionEvaluator.evaluate("x * 100 / 200", 100), delta)

        // x / N * M
        assertEquals(8f, SsmExpressionEvaluator.evaluate("x/100*8", 100), delta)

        // x * N ± M
        assertEquals(-2950f, SsmExpressionEvaluator.evaluate("x*25-3200", 10), delta)
        assertEquals(12.5f, SsmExpressionEvaluator.evaluate("x/4+10", 10), delta)
    }

    @Test
    fun testWhitespaceAndFormatting() {
        assertEquals(25f, SsmExpressionEvaluator.evaluate("  x  /  4  ", 100), delta)
        assertEquals(10f, SsmExpressionEvaluator.evaluate("x+0.0", 10), delta)
        assertEquals(0.123f, SsmExpressionEvaluator.evaluate("x* .123", 1), delta)
        assertEquals(
                0.5f,
                SsmExpressionEvaluator.evaluate("x/2.", 1),
                delta
        ) // 2. is matched by \d+ followed by .
    }

    //    @Test
    fun testUnknownFormatFallback() {
        // Should log a warning and return identity
        assertEquals(42f, SsmExpressionEvaluator.evaluate("x^2", 42), delta)
        assertEquals(123f, SsmExpressionEvaluator.evaluate("unknown", 123), delta)
    }
}
