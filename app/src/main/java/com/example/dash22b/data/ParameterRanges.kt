package com.example.dash22b.data

/**
 * Y-axis ranges for parameters.
 *
 * Static lookup data, deliberately separate from ParameterRegistry: it depends on
 * nothing the registry loads, so it can be exercised without an asset or an ECU.
 */
object ParameterRanges {

    /**
     * Fixed Y-axis ranges for parameters whose real-world span we are sure of.
     *
     * A fixed axis is better than autoscale for driving: the same needle position
     * means the same thing every time, and a flat trace reads as flat instead of
     * being stretched to fill the box by noise. Anything NOT listed here autoscales
     * instead -- see [getExpectedRange] -- because a wrong fixed range is worse than
     * no fixed range. Names cover both the logger XML spelling and the shorter
     * hardcoded/preset spelling, since either registry may be live.
     */
    private val minMaxMap = mapOf<String, RangeWithUnit>(
        // Engine
        "Engine Speed" to RangeWithUnit(0f, 8000f, DisplayUnit.RPM),
        "RPM" to RangeWithUnit(0f, 8000f, DisplayUnit.RPM),
        "Vehicle Speed" to RangeWithUnit(0f, 250f, DisplayUnit.KMH),

        // Temperatures
        "Coolant Temperature" to RangeWithUnit(20f, 150f, DisplayUnit.C),
        "Coolant temperature" to RangeWithUnit(20f, 150f, DisplayUnit.C),
        "Coolant Temp" to RangeWithUnit(20f, 150f, DisplayUnit.C),
        "Intake Air Temperature" to RangeWithUnit(-20f, 100f, DisplayUnit.C),
        "Intake Air Temp" to RangeWithUnit(-20f, 100f, DisplayUnit.C),
        "Intake Temp" to RangeWithUnit(-20f, 100f, DisplayUnit.C),

        // Pressures
        // FTP is one byte through (x-128)/40, so -3.2..+3.175 kPa is the whole
        // physical range of the sensor. It was previously listed as +-2 BAR, i.e.
        // +-200 kPa: 60x too wide, which drew the EVAP signal as a flat line.
        // Confirmed against 2026-08-29 logs, quantised to 0.025 kPa = 1/40.
        "Fuel Tank Pressure" to RangeWithUnit(-3.2f, 3.2f, DisplayUnit.KPA),
        // Sea level is ~101 kPa; the low end covers roughly 10,000 ft.
        "Atmospheric Pressure" to RangeWithUnit(70f, 110f, DisplayUnit.KPA),
        "Manifold Absolute Pressure" to RangeWithUnit(0f, 250f, DisplayUnit.KPA),
        "MAP" to RangeWithUnit(0f, 250f, DisplayUnit.KPA),
        // One byte through (x-128), so -128..+127 kPa is the sensor's whole range;
        // anything wider would put part of the axis out of the ECU's reach.
        "Manifold Relative Pressure" to RangeWithUnit(-128f, 127f, DisplayUnit.KPA),

        // Percentages
        "Throttle Opening Angle" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),
        "Throttle Position" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),
        "Absolute Throttle Position" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),
        "Throttle" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),
        "Injector Duty Cycle" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),
        "Inj. Duty Cycle" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),
        "Inj Duty Cycle" to RangeWithUnit(0f, 100f, DisplayUnit.PERCENT),

        // Timing. Knock correction is retard, so it is zero or negative; the small
        // positive headroom keeps a healthy 0 line off the very top edge.
        // Light-load cruise timing can reach the high 40s on this engine, so leave
        // headroom above it rather than clipping the top of the trace.
        "Ignition Timing" to RangeWithUnit(-20f, 60f, DisplayUnit.DEGREES),
        "Knock Correction Advance" to RangeWithUnit(-15f, 2f, DisplayUnit.DEGREES),
        "Knock Correction" to RangeWithUnit(-15f, 2f, DisplayUnit.DEGREES),
        "Fine Learning Knock Correction" to RangeWithUnit(-15f, 2f, DisplayUnit.DEGREES),

        // Airflow
        "Mass Airflow" to RangeWithUnit(0f, 300f, DisplayUnit.GRAMS_PER_SEC),
        "MAF" to RangeWithUnit(0f, 300f, DisplayUnit.GRAMS_PER_SEC),

        // Electrical
        "Battery Voltage" to RangeWithUnit(2f, 22f, DisplayUnit.VOLTS),

        // Deliberately absent, and therefore autoscaled:
        //   "Boost"  -- the logger XML defines it with units "raw value", so its
        //          scale depends on which registry is live. Not safe to pin.
        //   "DAM"    -- 0..16 on some ECUs, 0..1 on others.
        //   A/F correction / learning, Engine Load (g/rev), and everything else
        //   we have not measured on this car.
    )


    /**
     * Range for a graph, or null when we have no confident range and the graph should
     * autoscale to the data instead.
     *
     * Distinct from [min]/[max], which must always return a number because a circular
     * gauge cannot autoscale -- a dial with a moving scale is unreadable. A line graph
     * can, and a wrong fixed range hides the trace entirely.
     */
    fun expected(def: ParameterDefinition?, targetUnit: DisplayUnit): Pair<Float, Float>? {
        if (def == null) return null
        ParameterCalibration.getRange(def.name, targetUnit)?.let { return it }
        val mm = minMaxMap[def.name] ?: return null
        return UnitConverter.convert(mm.min, mm.unit, targetUnit) to
            UnitConverter.convert(mm.max, mm.unit, targetUnit)
    }

    /** Total lower bound, for gauges. Falls back to 0 (or -12 V) when unknown. */
    fun min(def: ParameterDefinition?, targetUnit: DisplayUnit): Float {
        expected(def, targetUnit)?.let { return it.first }
        if (def?.unit == DisplayUnit.VOLTS) return -12f
        return 0f
    }

    /** Total upper bound, for gauges. Falls back to 100 (or +12 V) when unknown. */
    fun max(def: ParameterDefinition?, targetUnit: DisplayUnit): Float {
        expected(def, targetUnit)?.let { return it.second }
        if (def?.unit == DisplayUnit.VOLTS) return 12f
        return 100f
    }
}
