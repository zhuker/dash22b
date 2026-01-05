package com.example.dash22b.data

object UnitConverter {
    fun convert(value: Float, fromUnit: String, toUnit: String): Float {
        if (fromUnit.equals(toUnit, ignoreCase = true)) return value

        // Normalize units
        val from = normalize(fromUnit)
        val to = normalize(toUnit)

        return when {
            // Pressure
            (from == "psi" && to == "bar") -> value * 0.0689476f
            (from == "kpa" && to == "bar") -> value * 0.01f
            (from == "bar" && to == "psi") -> value * 14.5038f
            (from == "bar" && to == "kpa") -> value * 100f
            
            // Temperature
            (from == "f" && to == "c") -> (value - 32f) * 5f / 9f
            (from == "c" && to == "f") -> (value * 9f / 5f) + 32f
            
            // Speed
            (from == "mph" && to == "km/h") -> value * 1.60934f
            (from == "km/h" && to == "mph") -> value / 1.60934f
            
            else -> value // Unknown conversion, return raw
        }
    }

    private fun normalize(unit: String): String {
        return unit.trim().lowercase().replace(Regex("[^a-z/]"), "")
    }
}
