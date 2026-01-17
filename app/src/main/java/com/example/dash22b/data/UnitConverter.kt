package com.example.dash22b.data

object UnitConverter {
    fun convert(value: Float, fromUnit: String, toUnit: String): Float {
        if (fromUnit.equals(toUnit, ignoreCase = true)) return value

        // Normalize units
        val from = normalize(fromUnit)
        val to = normalize(toUnit)

        return when {
            (from == "dam" && to == "multiplier") -> value
            (from == "multiplier" && to == "dam") -> value
            (from == "°" && to == "degrees") -> value
            (from == "degrees" && to == "°") -> value
            (from == "lambda" && to == "afr") -> 14.7f / value
            (from == "afr" && to == "lambda") -> 14.7f * value
            // Pressure
            (from == "psi" && to == "kpa") -> value * 6.89475729f
            (from == "kpa" && to == "psi") -> value * 0.145038f

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
            
            else -> {
//                println("Unknown conversion from '$fromUnit' to '$toUnit'")
                return value
            }
        }
    }

    private fun normalize(unit: String): String {
        return unit.trim().lowercase()
    }
}
