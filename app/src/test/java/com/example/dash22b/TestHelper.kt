package com.example.dash22b

import com.example.dash22b.obd.SsmEcuInit
import com.example.dash22b.obd.SsmLoggerDefinitionParser
import com.example.dash22b.obd.SsmParameter
import java.io.File

object TestHelper {
    fun stiParams(): List<SsmParameter> {
        val xmlFile = File("src/main/assets/logger_METRIC_EN_v370.xml")
        if (!xmlFile.exists()) {
            throw Exception("XML file not found, skipping test. Current dir: ${File(".").absolutePath}")
        }

        val ecuInit = SsmEcuInit.createHardcoded()
        val allParams = xmlFile.inputStream().use { inputStream ->
            SsmLoggerDefinitionParser.parseParameters(inputStream, ecuInit, 1)
        }
        return allParams
    }
}