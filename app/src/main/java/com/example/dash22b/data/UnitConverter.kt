package com.example.dash22b.data

import timber.log.Timber

enum class DisplayUnit(vararg val names: String) {
    UNKNOWN(""),

    // Temperature units
    C("°C"),
    F("°F"),

    // Pressure units
    BAR("bar"),
    PSI("psi"),
    KPA("kPa"),

    // Speed units
    MPH("mph"),
    KMH("km/h"),

    // Air/Fuel ratio units
    LAMBDA("lambda"),
    AFR("AFR"),

    // Angle units
    DEGREES("°", "deg"),

    // Other units
    RPM("rpm"),
    PERCENT("%"),
    VOLTS("V"),
    MILLIAMPS("mA"),
    MILLISECONDS("ms"),
    GRAMS_PER_SEC("g/s"),
    GRAMS_PER_REV("g/rev"),
    DAM("dam"),
    MULTIPLIER("multiplier"),
    SWITCH("switch");

    fun getCompatibleUnits(): List<DisplayUnit> {
        return when (this) {
            BAR, PSI, KPA -> listOf(BAR, PSI, KPA)
            C, F -> listOf(C, F)
            MPH, KMH -> listOf(MPH, KMH)
            LAMBDA, AFR -> listOf(LAMBDA, AFR)
            DAM, MULTIPLIER -> listOf(DAM, MULTIPLIER)
            else -> listOf(this)
        }
    }

    fun displayName(): String {
        return names[0]
    }

    companion object {

        fun fromString(value: String): DisplayUnit {
            for (unit in DisplayUnit.entries) {
                if (unit.names.contains(value)) {
                    return unit
                }
            }
            Timber.w("unknown unit '$value'")
            return UNKNOWN
        }
    }
}

object UnitConverter {
    fun convert(value: Float, from: DisplayUnit, to: DisplayUnit): Float {
        if (from == to) return value
        if (from == DisplayUnit.UNKNOWN) return value

        return when {
            (from == DisplayUnit.DAM && to == DisplayUnit.MULTIPLIER) -> value
            (from == DisplayUnit.MULTIPLIER && to == DisplayUnit.DAM) -> value
            (from == DisplayUnit.LAMBDA && to == DisplayUnit.AFR) -> 14.7f * value
            (from == DisplayUnit.AFR && to == DisplayUnit.LAMBDA) -> value / 14.7f
            // Pressure
            (from == DisplayUnit.PSI && to == DisplayUnit.KPA) -> value * 6.89475729f
            (from == DisplayUnit.KPA && to == DisplayUnit.PSI) -> value * 0.145038f

            (from == DisplayUnit.PSI && to == DisplayUnit.BAR) -> value * 0.0689476f
            (from == DisplayUnit.KPA && to == DisplayUnit.BAR) -> value * 0.01f
            (from == DisplayUnit.BAR && to == DisplayUnit.PSI) -> value * 14.5038f
            (from == DisplayUnit.BAR && to == DisplayUnit.KPA) -> value * 100f

            // Temperature
            (from == DisplayUnit.F && to == DisplayUnit.C) -> (value - 32f) * 5f / 9f
            (from == DisplayUnit.C && to == DisplayUnit.F) -> (value * 9f / 5f) + 32f

            // Speed
            (from == DisplayUnit.MPH && to == DisplayUnit.KMH) -> value * 1.60934f
            (from == DisplayUnit.KMH && to == DisplayUnit.MPH) -> value / 1.60934f

            else -> {
                Timber.w("Unknown conversion from '$from' to '$to'")
                return value
            }
        }
    }

}
