package com.example.dash22b.obd

import com.example.dash22b.data.Unit
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Unit tests for SSM Logger Definition XML parsing.
 *
 * This test parses the logger_METRIC_EN_v370.xml file and prints all available parameters
 * for manual comparison with known good parameter lists.
 *
 * Run this test to see which parameters are available for your ECU ROM ID.
 */
class SsmLoggerDefinitionParserTest {

    // TODO: Replace hardcoded ROM ID with actual ROM ID from serial cable connection
    // This ROM ID should be retrieved dynamically when the ECU is connected
    private val testRomId = "A210113D12"

    /**
     * Test parsing with hardcoded ECU init (all parameters supported).
     * Prints all parsed parameters for manual verification.
     */
    @Test
    fun testParseAllParameters() {
        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found at: ${xmlFile.absolutePath}")
            println("Skipping test - XML file required")
            return
        }

        // Create hardcoded ECU init with all capabilities enabled
        val ecuInit = SsmEcuInit.createHardcoded()

        println("=".repeat(80))
        println("SSM Logger Definition Parser Test")
        println("ROM ID: $testRomId (hardcoded for testing)")
        println("=".repeat(80))

        xmlFile.inputStream().use { inputStream ->
            val parameters = SsmLoggerDefinitionParser.parseParameters(
                inputStream = inputStream,
                ecuInit = ecuInit,
                target = 1 // ECU only
            )

            println("\nParsed ${parameters.size} parameters:\n")
            println(
                String.format(
                    "%-6s %-40s %-10s %-6s %-20s %s",
                    "ID", "Name", "Address", "Len", "Unit", "Expression"
                )
            )
            println("-".repeat(100))

            parameters.sortedBy { it.id }.forEach { param ->
                println(
                    String.format(
                        "%-6s %-40s 0x%06X %-6d %-20s %s",
                        param.id,
                        param.name.take(40),
                        param.address,
                        param.length,
                        param.unit.displayName(),
                        param.expression
                    )
                )
            }

            println("\n" + "=".repeat(80))
            println("Total: ${parameters.size} parameters")
            println("=".repeat(80))

            // Basic assertions
            assertTrue("Should parse some parameters", parameters.isNotEmpty())

            // Check for known critical parameters
            val engineSpeed = parameters.find { it.id == "P8" }
            assertNotNull("Should have Engine Speed (P8)", engineSpeed)
            assertEquals("Engine Speed", engineSpeed?.name)
            assertEquals(0x00000E, engineSpeed?.address)
            assertEquals(2, engineSpeed?.length)
            assertEquals("x/4", engineSpeed?.expression)

            val coolantTemp = parameters.find { it.id == "P2" }
            assertNotNull("Should have Coolant Temperature (P2)", coolantTemp)
            assertEquals(0x000008, coolantTemp?.address)
            assertEquals("x-40", coolantTemp?.expression)
        }
    }

    /**
     * Test parsing without ECU init (include all parameters regardless of capability).
     */
    @Test
    fun testParseWithoutEcuInit() {
        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found, skipping test")
            return
        }

        xmlFile.inputStream().use { inputStream ->
            val parameters = SsmLoggerDefinitionParser.parseParameters(
                inputStream = inputStream,
                ecuInit = null, // No filtering
                target = 1
            )

            println("\n" + "=".repeat(80))
            println("Parsed ${parameters.size} parameters WITHOUT capability filtering")
            println("=".repeat(80))

            // Check if P3-P9 are present
            val p3to9 =
                parameters.filter { it.id in listOf("P3", "P4", "P5", "P6", "P7", "P8", "P9") }
            println("Parameters P3-P9 found: ${p3to9.map { it.id }}")

            assertTrue("Should parse parameters", parameters.isNotEmpty())
        }
    }

    /**
     * Test that specific known parameters are parsed correctly.
     */
    @Test
    fun testKnownParameters() {
        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found, skipping test")
            return
        }

        val ecuInit = SsmEcuInit.createHardcoded()

        xmlFile.inputStream().use { inputStream ->
            val parameters = SsmLoggerDefinitionParser.parseParameters(inputStream, ecuInit, 1)
            val paramMap = parameters.associateBy { it.id }
            paramMap.forEach {
                println(it)
            }

            // Verify parameters match SsmHardcodedParameters values
            println("\nComparing with SsmHardcodedParameters:")
            println("-".repeat(60))

            SsmHardcodedParameters.parameters.forEach { hardcoded ->
                val parsed = paramMap[hardcoded.id]
                if (parsed != null) {
                    println("✓ ${hardcoded.id}: ${hardcoded.name}")
                    println(
                        "  Address: hardcoded=0x${hardcoded.address.toString(16).uppercase()}, " +
                                "parsed=0x${parsed.address.toString(16).uppercase()}"
                    )
                    println("  Length: hardcoded=${hardcoded.length}, parsed=${parsed.length}")
                    println("  Expression: hardcoded=${hardcoded.expression}, parsed=${parsed.expression}")

                    // These should match
                    assertEquals(
                        "Address mismatch for ${hardcoded.id}",
                        hardcoded.address, parsed.address
                    )
                    assertEquals(
                        "Length mismatch for ${hardcoded.id}",
                        hardcoded.length, parsed.length
                    )
                } else {
                    println("✗ ${hardcoded.id}: ${hardcoded.name} - NOT FOUND in parsed parameters")
                }
            }
        }
    }

    /**
     * Print parameters in a format suitable for comparison with other tools.
     */
    @Test
    fun testPrintParametersForComparison() {
        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found, skipping test")
            return
        }

        val ecuInit = SsmEcuInit.createHardcoded()

        xmlFile.inputStream().use { inputStream ->
            val parameters = SsmLoggerDefinitionParser.parseParameters(inputStream, ecuInit, 1)

            println("\n" + "=".repeat(80))
            println("PARAMETERS FOR ROM ID: $testRomId")
            println("Format: ID | Name | Address | Unit")
            println("Use this list to compare with RomRaider or other SSM tools")
            println("=".repeat(80) + "\n")

            parameters.sortedBy { it.id }.forEach { param ->
                println(
                    "${param.id} | ${param.name} | 0x${
                        param.address.toString(16).uppercase().padStart(6, '0')
                    } | ${param.unit.displayName()}"
                )
            }

            println("\n" + "=".repeat(80))
            println("Total parameters: ${parameters.size}")
            println("=".repeat(80))
        }
    }

    /**
     * Test that ECU-specific parameters (ecuparams) are parsed when a matching ROM ID is provided.
     */
    @Test
    fun testEcuParams() {
        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found, skipping test")
            return
        }

        // Use the hardcoded init which contains the real 2005 STi response
        // With the fix, this should now resolve to ROM ID 3D12594006
        val ecuInit = SsmEcuInit.createHardcoded()

        println("Testing ECU params for ROM ID: ${ecuInit.getRomId()}")

        xmlFile.inputStream().use { inputStream ->
            val parameters = SsmLoggerDefinitionParser.parseParameters(inputStream, ecuInit, 1)

            val ecuParams = parameters.filter { it.id.startsWith("E") }
            println("Found ${ecuParams.size} ECU-specific parameters (E*)")

            ecuParams.sortedBy { it.id }.forEach {
                println("  ${it.id}: ${it.name} @ 0x${it.address.toString(16).uppercase()}")
            }

            assertTrue(
                "Should find some ECU-specific parameters for this ROM",
                ecuParams.isNotEmpty()
            )

            // Basic validity checks
            ecuParams.forEach {
                assertNotNull("Address should be present", it.address)
                assertTrue("Address should be positive", it.address > 0)
            }
        }
    }

    @Test
    fun testListAllSupportedParameters() {
        val expectedParameters: Map<String, String> =
            File("src/main/assets/2005_STi_SSM_Parameters_Ranges.csv").useLines { lines ->
                lines.drop(1)
                    .map { val tokens = it.split(",").take(2).toList(); tokens[0] to tokens[1] }
                    .toMap()
            }

        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            println("XML file not found, skipping test")
            return
        }

        val ecuInit = SsmEcuInit.createHardcoded()
        println("Listing ALL supported parameters for ROM ID: ${ecuInit.getRomId()}")

        val parameters = xmlFile.inputStream().use { inputStream ->
            SsmLoggerDefinitionParser.parseParameters(inputStream, ecuInit, 1)
        }
        assertTrue(!parameters.isEmpty())

        println("Found parameters:")
        val standards = parameters.filter { !it.id.startsWith("E") && it.unit != Unit.SWITCH }
        standards.forEach { println("P: ${it.id} - ${it.name} (${it.address})") }

        val switches = parameters.filter { it.unit == Unit.SWITCH }
        if (switches.isNotEmpty()) {
            println("\nFound switches:")
            switches.forEach { println("S: ${it.id} - ${it.name} (${it.address})") }
        }

        val extended = parameters.filter { it.id.startsWith("E") }
        if (extended.isNotEmpty()) {
            println("\nFound extended parameters:")
            extended.forEach { println("E: ${it.id} - ${it.name} (${it.address})") }
        }

        println("\nTotal: ${parameters.size} (Std: ${standards.size}, Sw: ${switches.size}, Ext: ${extended.size})")

        // Assertions from original code, kept as they are not explicitly removed
        val standardParams = parameters.filter { it.id.startsWith("P") }
        val extendedParams = parameters.filter { it.id.startsWith("E") }
        println("\n--- Extended Parameters ---")
        extendedParams.sortedBy { it.id }.forEach {
            println("${it.id}: ${it.name} [Addr: 0x${it.address.toString(16).uppercase()}]")
        }

        assertTrue("Should find standard parameters", standardParams.isNotEmpty())
        assertTrue("Should find extended parameters", extendedParams.isNotEmpty())

        val paramById = parameters.associateBy { it.id }
        paramById.forEach {
            assertTrue(
                "expected ${it.key} in expectedParameters",
                expectedParameters.containsKey(it.key)
            )
        }
        expectedParameters.forEach {
            if (it.key == "S157" || it.value == "Calculated") {
                println("not supported $it")
            } else {
                assertTrue(
                    "expected ${it.key} in parsed parameters",
                    paramById.containsKey(it.key),
                )
            }
        }

    }
}
