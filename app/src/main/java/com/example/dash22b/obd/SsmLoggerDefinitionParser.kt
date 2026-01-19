package com.example.dash22b.obd

import com.example.dash22b.data.Unit
import timber.log.Timber
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Parser for RomRaider logger definition XML files (e.g., logger_METRIC_EN_v370.xml).
 *
 * This parser extracts standard parameters from the SSM protocol section and filters them
 * based on ECU capability bits from the init response.
 *
 * Based on RomRaider's LoggerDefinitionHandler.java implementation.
 * Uses standard Java DOM parser for compatibility with unit tests.
 */
class SsmLoggerDefinitionParser(private val ecuInit: SsmEcuInit?) {

    /**
     * Parse the XML input stream and return a list of supported parameters.
     *
     * @param inputStream The XML file input stream
     * @param target Target filter: 1=ECU only, 2=TCU only, 3=both (default: include all ECU-compatible)
     * @return List of SsmParameter objects that are supported by the ECU
     */
    fun parse(inputStream: InputStream, target: Int = 1): List<SsmParameter> {
        val parameters = mutableListOf<SsmParameter>()

        val factory = DocumentBuilderFactory.newInstance()
        // Disable DTD validation to speed up parsing
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(inputStream)

        // Initialize ROM ID once if available
        val romId = ecuInit?.getRomId()

        // Find SSM protocol
        val protocols = document.getElementsByTagName("protocol")
        for (i in 0 until protocols.length) {
            val protocol = protocols.item(i) as Element
            if (protocol.getAttribute("id") == "SSM") {
                // Find parameters section
                val parametersSections = protocol.getElementsByTagName("parameters")
                for (j in 0 until parametersSections.length) {
                    val parametersSection = parametersSections.item(j) as Element
                    val parameterNodes = parametersSection.childNodes
                    for (k in 0 until parameterNodes.length) {
                        val node = parameterNodes.item(k)
                        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "parameter") {
                            val param = parseParameter(node as Element, target)
                            if (param != null) {
                                parameters.add(param)
                            }
                        }
                    }
                }

                // Find ecuparams section (sibling of parameters)
                if (romId != null) {
                    val ecuparamsSections = protocol.getElementsByTagName("ecuparams")
                    for (j in 0 until ecuparamsSections.length) {
                        val ecuparamsSection = ecuparamsSections.item(j) as Element
                        val ecuparamNodes = ecuparamsSection.childNodes
                        for (k in 0 until ecuparamNodes.length) {
                            val node = ecuparamNodes.item(k)
                            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "ecuparam") {
                                val param = parseEcuParam(node as Element, target, romId)
                                if (param != null) {
                                    parameters.add(param)
                                }
                            }
                        }
                    }
                }

                // Find switches section (sibling of parameters)
                val switchesSections = protocol.getElementsByTagName("switches")
                for (j in 0 until switchesSections.length) {
                    val switchesSection = switchesSections.item(j) as Element
                    val switchNodes = switchesSection.childNodes
                    for (k in 0 until switchNodes.length) {
                        val node = switchNodes.item(k)
                        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "switch") {
                            val param = parseSwitch(node as Element, target)
                            if (param != null) {
                                parameters.add(param)
                            }
                        }
                    }
                }
            }
        }

        Timber.d("Parsed ${parameters.size} standard parameters from XML")

        return parameters
    }

    /**
     * Parse a single <parameter> element.
     * Returns null if the parameter is not supported by the ECU or doesn't match the target.
     */
    private fun parseParameter(element: Element, targetFilter: Int): SsmParameter? {
        val id = element.getAttribute("id").takeIf { it.isNotEmpty() } ?: return null
        val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: return null
        val ecuByteIndexStr = element.getAttribute("ecubyteindex").takeIf { it.isNotEmpty() }
        val ecuBitStr = element.getAttribute("ecubit").takeIf { it.isNotEmpty() }
        val targetStr = element.getAttribute("target").takeIf { it.isNotEmpty() } ?: "1"

        // Check target compatibility
        val paramTarget = targetStr.toIntOrNull() ?: 1
        if (paramTarget != 3 && paramTarget != targetFilter) {
            // Skip this parameter - not compatible with our target
            return null
        }

        // Check capability bits if ECU init is available
        if (ecuInit != null && ecuByteIndexStr != null && ecuBitStr != null) {
            val ecuByteIndex = ecuByteIndexStr.toIntOrNull() ?: 0
            val ecuBit = ecuBitStr.toIntOrNull() ?: 0
            if (!ecuInit.isParameterSupported(ecuByteIndex, ecuBit)) {
                Timber.v("Parameter $id ($name) not supported by ECU (byte=$ecuByteIndex, bit=$ecuBit)")
                return null
            }
        }

        // Parse address
        var address: Int? = null
        var length = 1
        val addressNodes = element.getElementsByTagName("address")
        if (addressNodes.length > 0) {
            val addressElement = addressNodes.item(0) as Element
            val lengthStr = addressElement.getAttribute("length")
            if (lengthStr.isNotEmpty()) {
                length = lengthStr.toIntOrNull() ?: 1
            }
            address = parseHexAddress(addressElement.textContent)
        }

        // Parse first conversion
        var expression: String? = null
        var unit: Unit = Unit.UNKNOWN
        val conversionNodes = element.getElementsByTagName("conversion")
        if (conversionNodes.length > 0) {
            val conversionElement = conversionNodes.item(0) as Element
            expression = conversionElement.getAttribute("expr")
            val unitStr = conversionElement.getAttribute("units")
            unit = parseUnit(unitStr)
        }

        // Validate required fields
        if (address == null || expression == null) {
            Timber.w("Parameter $id ($name) missing address or expression")
            return null
        }

        return SsmParameter(
            id = id,
            name = name,
            address = address,
            length = length,
            expression = expression,
            unit = unit
        )
    }

    /**
     * Parse a single <ecuparam> element.
     * Scans internal <ecu> elements to find one that matches the connected ECU's ROM ID.
     */
    private fun parseEcuParam(element: Element, targetFilter: Int, romId: String): SsmParameter? {
        val id = element.getAttribute("id").takeIf { it.isNotEmpty() } ?: return null
        val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: return null
        val targetStr = element.getAttribute("target").takeIf { it.isNotEmpty() } ?: "1"

        // Check target compatibility
        val paramTarget = targetStr.toIntOrNull() ?: 1
        if (paramTarget != 3 && paramTarget != targetFilter) {
            return null
        }

        // Find matching ECU definition
        var address: Int? = null
        var length = 1
        var matched = false

        val ecuNodes = element.getElementsByTagName("ecu")
        for (i in 0 until ecuNodes.length) {
            val ecuElement = ecuNodes.item(i) as Element
            val idList = ecuElement.getAttribute("id")
            
            // ID list is comma-separated ROM IDs
            if (idList.split(",").contains(romId)) {
                matched = true
                
                // Parse address from this ECU block
                val addressNodes = ecuElement.getElementsByTagName("address")
                if (addressNodes.length > 0) {
                    val addressElement = addressNodes.item(0) as Element
                    val lengthStr = addressElement.getAttribute("length")
                    if (lengthStr.isNotEmpty()) {
                        length = lengthStr.toIntOrNull() ?: 1
                    }
                    address = parseHexAddress(addressElement.textContent)
                }
                break // Found our ECU, stop searching
            }
        }

        if (!matched || address == null) {
            return null // Not supported by this specific ECU
        }

        // Parse conversions (common to all ECUs for this parameter)
        var expression: String? = null
        var unit: Unit = Unit.UNKNOWN
        val conversionNodes = element.getElementsByTagName("conversion")
        if (conversionNodes.length > 0) {
            val conversionElement = conversionNodes.item(0) as Element
            expression = conversionElement.getAttribute("expr")
            val unitStr = conversionElement.getAttribute("units")
            unit = parseUnit(unitStr)
        }

        if (expression == null) {
            return null
        }

        return SsmParameter(
            id = id,
            name = name,
            address = address,
            length = length,
            expression = expression,
            unit = unit
        )
    }

    /**
     * Parse a single <switch> element.
     */
    private fun parseSwitch(element: Element, targetFilter: Int): SsmParameter? {
        val id = element.getAttribute("id").takeIf { it.isNotEmpty() } ?: return null
        val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: return null
        val ecuByteIndexStr = element.getAttribute("ecubyteindex").takeIf { it.isNotEmpty() }
        // Switches use 'bit' for both capability check and value extraction
        val ecuBitStr = element.getAttribute("bit").takeIf { it.isNotEmpty() }
        val targetStr = element.getAttribute("target").takeIf { it.isNotEmpty() } ?: "1"
        val byteStr = element.getAttribute("byte").takeIf { it.isNotEmpty() }
        val bitStr = element.getAttribute("bit").takeIf { it.isNotEmpty() }

        // Check target compatibility
        val paramTarget = targetStr.toIntOrNull() ?: 1
        if (paramTarget != 3 && paramTarget != targetFilter) {
            return null
        }

        // Check capability bits if ECU init is available
        if (ecuInit != null && ecuByteIndexStr != null && ecuBitStr != null) {
            val ecuByteIndex = ecuByteIndexStr.toIntOrNull() ?: 0
            val ecuBit = ecuBitStr.toIntOrNull() ?: 0

            if (!ecuInit.isParameterSupported(ecuByteIndex, ecuBit)) {
                Timber.v("Switch $id ($name) not supported by ECU (byte=$ecuByteIndex, bit=$ecuBit)")
                return null
            }
        }

        // Parse address
        if (byteStr == null) {
            Timber.w("Switch $id ($name) missing byte address")
            return null
        }
        val address = parseHexAddress(byteStr) ?: return null
        
        // Construct expression for switch
        val bit = bitStr?.toIntOrNull() ?: 0
        // Expression to extract bit: (x & (1<<bit)) != 0
        // Wait, SsmParameter expects an expression to evaluate to a number.
        // We can just store the bit index in the expression for now, or construct a special expression.
        // Or leave expression null? SsmParameter requires non-null expression.
        // Let's use a convention: "bit:N"
        val expression = "bit:$bit"

        return SsmParameter(
            id = id,
            name = name,
            address = address,
            length = 1, // Switches are always single byte reads
            expression = expression, // Special expression for switches
            unit = Unit.SWITCH
        )
    }

    /**
     * Parse a hex address string like "0x00000E" to an integer.
     */
    private fun parseHexAddress(addressStr: String): Int? {
        val cleaned = addressStr.trim().removePrefix("0x").removePrefix("0X")
        return try {
            cleaned.toInt(16)
        } catch (e: NumberFormatException) {
            Timber.w("Failed to parse address: $addressStr")
            null
        }
    }

    /**
     * Map XML unit string to Unit enum.
     */
    private fun parseUnit(unitStr: String): Unit {
        return when (unitStr.lowercase()) {
            "rpm" -> Unit.RPM
            "c", "°c" -> Unit.C
            "f", "°f" -> Unit.F
            "%", "percent" -> Unit.PERCENT
            "v", "volts" -> Unit.VOLTS
            "kpa" -> Unit.KPA
            "bar" -> Unit.BAR
            "psi" -> Unit.PSI
            "km/h" -> Unit.KMH
            "mph" -> Unit.MPH
            "g/s" -> Unit.GRAMS_PER_SEC
            "g/rev" -> Unit.GRAMS_PER_REV
            "degrees", "°" -> Unit.DEGREES
            "ms" -> Unit.MILLISECONDS
            "ma" -> Unit.MILLIAMPS
            "lambda" -> Unit.LAMBDA
            "afr" -> Unit.AFR
            "multiplier" -> Unit.MULTIPLIER
            "switch" -> Unit.SWITCH
            else -> {
                Timber.v("Unknown unit: $unitStr")
                Unit.UNKNOWN
            }
        }
    }

    companion object {
        /**
         * Convenience method to parse XML and return all parameters supported by the given ECU.
         *
         * @param inputStream XML input stream
         * @param ecuInit ECU initialization data (null to include all parameters)
         * @param target Target filter (1=ECU, 2=TCU, 3=both)
         */
        fun parseParameters(
            inputStream: InputStream,
            ecuInit: SsmEcuInit? = null,
            target: Int = 1
        ): List<SsmParameter> {
            val parser = SsmLoggerDefinitionParser(ecuInit)
            return parser.parse(inputStream, target)
        }
    }
}
