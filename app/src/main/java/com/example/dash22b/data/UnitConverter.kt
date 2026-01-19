package com.example.dash22b.data

import timber.log.Timber

enum class Unit(vararg val names: String) {
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

    /**
     * Returns the primary display name for this unit.
     * Returns empty string for UNKNOWN.
     */
    fun displayName(): String = names.firstOrNull() ?: ""

    companion object {

        fun fromString(value: String): Unit {
            for (unit in Unit.entries) {
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
    fun convert(value: Float, from: Unit, to: Unit): Float {
        if (from == to) return value
        if (from == Unit.UNKNOWN) return value

        return when {
            (from == Unit.DAM && to == Unit.MULTIPLIER) -> value
            (from == Unit.MULTIPLIER && to == Unit.DAM) -> value
            (from == Unit.LAMBDA && to == Unit.AFR) -> 14.7f * value
            (from == Unit.AFR && to == Unit.LAMBDA) -> value / 14.7f
            // Pressure
            (from == Unit.PSI && to == Unit.KPA) -> value * 6.89475729f
            (from == Unit.KPA && to == Unit.PSI) -> value * 0.145038f

            (from == Unit.PSI && to == Unit.BAR) -> value * 0.0689476f
            (from == Unit.KPA && to == Unit.BAR) -> value * 0.01f
            (from == Unit.BAR && to == Unit.PSI) -> value * 14.5038f
            (from == Unit.BAR && to == Unit.KPA) -> value * 100f

            // Temperature
            (from == Unit.F && to == Unit.C) -> (value - 32f) * 5f / 9f
            (from == Unit.C && to == Unit.F) -> (value * 9f / 5f) + 32f

            // Speed
            (from == Unit.MPH && to == Unit.KMH) -> value * 1.60934f
            (from == Unit.KMH && to == Unit.MPH) -> value / 1.60934f

            else -> {
                Timber.w("Unknown conversion from '$from' to '$to'")
                return value
            }
        }
    }

}
