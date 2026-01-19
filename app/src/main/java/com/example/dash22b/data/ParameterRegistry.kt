package com.example.dash22b.data

import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

abstract class ParameterDefinition(
    val id: String,
    val type: String,
    val unit: DisplayUnit,
    val name: String, // Accessport Monitor Name (e.g., "RPM")
    val description: String,
    val minExpected: Float,
    val maxExpected: Float,
    val accessportName: String // The name used in log files
)

class ParameterDefinitionImpl(
    id: String,
    type: String,
    unit: DisplayUnit,
    name: String, // Accessport Monitor Name (e.g., "RPM")
    description: String,
    minExpected: Float,
    maxExpected: Float,
    accessportName: String // The name used in log files
) : ParameterDefinition(
    id,
    type,
    unit,
    name,
    description,
    minExpected,
    maxExpected,
    accessportName
) {
}

class ParameterRegistry private constructor(
    private val definitions: Map<String, ParameterDefinition>
) {
    companion object {
        private val manualMap = mapOf(
            "Comm Fuel Final" to "Final Fuel Base", // Commanded Fuel Final (AFR) = Stoichiometric AFR / Final Fueling Base (Lambda)
            "AF Correction 1" to "A/F Correction 1",
            "AF Learning 1" to "A/F Learning 1",
            "AF Sens 1 Ratio" to "A/F Sens 1 Ratio",
            "AF Sens 1 Curr" to "A/F Sens 1 Curr",
            "Inj Duty Cycle" to "Inj. Duty Cycle",
            "MAF" to "Mass Airflow",
            "AF Sens 3 Volts" to "Rear O2 Volts",
            "Dyn Adv Mult" to "DAM", // Dynamic Advance Multiplier (Dyn Adv Mult) -> This is a learned correction applied to dynamic advance. The dynamic advance multiplier (DAM) is one of three knock responses. When conditions dictate that a change to the DAM is to occur, the current knock signal is referenced and the DAM is set to an initial value. If a knock event has occurred, the DAM will decrease. If there's no knock event, the DAM will increase (if no knock over a delay period). The DAM is reset to an initial value after an ECU reset or after a reflash. For the 02-05 WRX, the DAM ranges from 0 to 16 and its application to dynamic advance can be calculated as follows: dynamic advance map value * (DAM/16). For all other ECUs, the DAM ranges from 0 to 1 (decimal value) and is applied as follows: dynamic advance map value * DAM.
            "TD Boost Error" to "Boost Error",
            "RPM" to "Engine Speed",
            "Intake Temp" to "Intake Air Temp",
        )

        /**
         * Factory method: Load parameter definitions from CSV file (Accessport format).
         */
        fun fromCsv(assetLoader: AssetLoader): ParameterRegistry {
            val definitions = sortedMapOf<String, ParameterDefinition>()

            try {
                val inputStream = assetLoader.open("2005_STi_SSM_Parameters_Ranges.csv")
                val reader = BufferedReader(InputStreamReader(inputStream))

                // Skip Header
                reader.readLine()

                var line = reader.readLine()
                while (line != null) {
                    // CSV Parsing (Handling commas inside quotes is tricky with simple split,
                    // but this specific file seems to quote fields nicely)
                    // Let's use a regex or simple split if safe. The provided file has quotes.
                    // Regex for splitting by comma but ignoring commas in quotes:
                    val tokens = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())

                    if (tokens.size >= 9) {
                        val def = ParameterDefinitionImpl(
                            id = tokens[0].trim(),
                            type = tokens[1].trim(),
                            unit = DisplayUnit.fromString(tokens[2].trim()),
                            name = tokens[3].replace("\"", "").trim(), // Original Name
                            description = tokens[4].replace("\"", "").trim(),
                            minExpected = tokens[5].trim().toFloatOrNull() ?: 0f,
                            maxExpected = tokens[7].trim().toFloatOrNull() ?: 100f, // Using WOT expected as Max often?
                            accessportName = tokens[8].trim() // Accessport Monitor Name
                        )

                        // Map by Accessport Name for easy lookup from Log File Headers
                        if (def.accessportName.isNotEmpty()) {
                            definitions[def.accessportName.lowercase()] = def
                        }
                    }
                    line = reader.readLine()
                }
                reader.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return ParameterRegistry(definitions)
        }

        /**
         * Factory method: Load hardcoded SSM parameters for live ECU data.
         */
        fun fromHardcodedSsm(): ParameterRegistry {
            val definitions = sortedMapOf<String, ParameterDefinition>()

            // Convert SsmParameter objects to ParameterDefinition objects
            com.example.dash22b.obd.SsmHardcodedParameters.parameters.forEach { ssmParam ->
                definitions[ssmParam.name.lowercase()] = ssmParam
            }

            return ParameterRegistry(definitions)
        }

        /**
         * Factory method: Load parameter definitions from XML file (RomRaider logger format).
         * Supports logger_METRIC_EN_v370.xml format with capability bit filtering.
         *
         * @param inputStream The XML file input stream
         * @param ecuInit ECU initialization data for capability filtering (null to include all)
         * @param target Target filter: 1=ECU, 2=TCU, 3=both (default: 1)
         */
        fun fromXml(
            inputStream: java.io.InputStream,
            ecuInit: com.example.dash22b.obd.SsmEcuInit? = null,
            target: Int = 1
        ): ParameterRegistry {
            val definitions = sortedMapOf<String, ParameterDefinition>()

            val ssmParameters = com.example.dash22b.obd.SsmLoggerDefinitionParser.parseParameters(
                inputStream = inputStream,
                ecuInit = ecuInit,
                target = target
            )

            // Convert SsmParameter objects to ParameterDefinition objects
            ssmParameters.forEach { ssmParam ->
                definitions[ssmParam.name.lowercase()] = ssmParam
            }

            Timber.d("Created ParameterRegistry with ${definitions.size} parameters from XML")
            return ParameterRegistry(definitions)
        }
    }

    fun getDefinition(accessportName: String): ParameterDefinition? {
        val key =
            if (definitions.containsKey(accessportName.lowercase())) {
                accessportName.lowercase()
            } else if (manualMap.containsKey(accessportName)) {
                manualMap[accessportName]?.lowercase()
            } else {
                ""
            }
        // Try exact match, then case insensitive
        if (!definitions.contains(key)) {
            Timber.w("oops cant find '$key' for '$accessportName'")
        }
        return definitions[key]
    }

    fun getAllDefinitions(): List<ParameterDefinition> {
        return definitions.values.distinctBy { it.accessportName }.sortedBy { it.accessportName }
    }
}
