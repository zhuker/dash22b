package com.example.dash22b.data

import java.io.BufferedReader
import java.io.InputStreamReader

data class ParameterDefinition(
    val id: String,
    val type: String,
    val unit: String,
    val name: String, // Accessport Monitor Name (e.g., "RPM")
    val description: String,
    val minExpected: String,
    val maxExpected: String,
    val accessportName: String // The name used in log files
) {
    // Helper to get float ranges from specific CSV format "0 - 100 %"
    fun getMinMax(): Pair<Float, Float> {
        // Very basic heuristic parser for the "Expected" fields which are messy strings
        // Example: "0.15 - 0.40 g/rev" -> 0.15, 0.40
        // Example: "2000 - 3500 rpm" -> 2000, 3500
        // Example: "0 %" -> 0, 100 (if max missing?)

        fun parseVal(s: String): Float? {
            val numStr = s.split(" ").firstOrNull { it.matches(Regex("-?\\d+(\\.\\d+)?")) }
            return numStr?.toFloatOrNull()
        }

        // We generally use the "WOT Expected" or "Cruise Expected" for Gauge Ranges?
        // Actually, let's just default to 0-100 for percentage, or try to parse.
        // For now, hardcode overrides for common types or just return 0-100 if fails.
        return 0f to 100f
    }
}

object ParameterRegistry {
    private val definitions = sortedMapOf<String, ParameterDefinition>()
    private var isInitialized = false

    fun initialize(assetLoader: AssetLoader) {
        if (isInitialized) return

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
                    val def = ParameterDefinition(
                        id = tokens[0].trim(),
                        type = tokens[1].trim(),
                        unit = tokens[2].trim(),
                        name = tokens[3].replace("\"", "").trim(), // Original Name
                        description = tokens[4].replace("\"", "").trim(),
                        minExpected = tokens[5].trim(),
                        maxExpected = tokens[7].trim(), // Using WOT expected as Max often?
                        accessportName = tokens[8].trim() // Accessport Monitor Name
                    )

                    // Map by Accessport Name for easy lookup from Log File Headers
                    if (def.accessportName.isNotEmpty()) {
                        definitions[def.accessportName.lowercase()] = def
                    }
                }
                line = reader.readLine()
            }
            isInitialized = true
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val manualMap = mapOf(
        "Comm Fuel Final" to  "Final Fuel Base", // Commanded Fuel Final (AFR) = Stoichiometric AFR / Final Fueling Base (Lambda)
        "AF Correction 1" to "A/F Correction 1",
        "AF Learning 1" to "A/F Learning 1",
        "AF Sens 1 Ratio" to "A/F Sens 1 Ratio",
        "AF Sens 1 Curr" to "A/F Sens 1 Curr",
        "Inj Duty Cycle" to "Inj. Duty Cycle",
        "MAF" to "Mass Airflow",
        "AF Sens 3 Volts" to "Rear O2 Volts",
        "Dyn Adv Mult" to "DAM", // Dynamic Advance Multiplier (Dyn Adv Mult) -> This is a learned correction applied to dynamic advance. The dynamic advance multiplier (DAM) is one of three knock responses. When conditions dictate that a change to the DAM is to occur, the current knock signal is referenced and the DAM is set to an initial value. If a knock event has occurred, the DAM will decrease. If there's no knock event, the DAM will increase (if no knock over a delay period). The DAM is reset to an initial value after an ECU reset or after a reflash. For the 02-05 WRX, the DAM ranges from 0 to 16 and its application to dynamic advance can be calculated as follows: dynamic advance map value * (DAM/16). For all other ECUs, the DAM ranges from 0 to 1 (decimal value) and is applied as follows: dynamic advance map value * DAM.
        "TD Boost Error" to "Boost Error",
    )

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
//        if (!definitions.contains(key)) {
//            println("oops cant find $key")
//        }
        return definitions[key]
    }

    fun getAllDefinitions(): List<ParameterDefinition> {
        return definitions.values.distinctBy { it.accessportName }.sortedBy { it.accessportName }
    }
}
