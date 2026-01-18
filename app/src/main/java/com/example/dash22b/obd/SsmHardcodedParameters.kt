package com.example.dash22b.obd

import com.example.dash22b.data.Unit

/**
 * Hardcoded SSM parameter definitions for real-time ECU monitoring.
 * These are the 8-15 most important parameters for smooth dashboard display.
 *
 * Based on RomRaider's logger_METRIC_EN_v370.xml parameter definitions.
 */
object SsmHardcodedParameters {
    val parameters = listOf(
        // Core engine parameters (required for dashboard)
        SsmParameter(
            id = "P8",
            name = "Engine Speed",
            address = 0x00000E,
            length = 2,
            expression = "x/4",
            unit = Unit.RPM
        ),
        SsmParameter(
            id = "P2",
            name = "Coolant Temp",
            address = 0x000008,
            length = 1,
            expression = "x-40",
            unit = Unit.C
        ),
        SsmParameter(
            id = "P25",
            name = "Boost",
            address = 0x000024,
            length = 1,
            expression = "x-128",
            unit = Unit.KPA
        ),
        SsmParameter(
            id = "P13",
            name = "Throttle",
            address = 0x000015,
            length = 1,
            expression = "x*100/255",
            unit = Unit.PERCENT
        ),
        SsmParameter(
            id = "P10",
            name = "Ignition Timing",
            address = 0x000011,
            length = 1,
            expression = "(x-128)/2",
            unit = Unit.DEGREES
        ),
        SsmParameter(
            id = "P23",
            name = "Knock Correction",
            address = 0x000022,
            length = 1,
            expression = "(x-128)/2",
            unit = Unit.DEGREES
        ),
        SsmParameter(
            id = "P11",
            name = "Intake Air Temp",
            address = 0x000012,
            length = 1,
            expression = "x-40",
            unit = Unit.C
        ),
        SsmParameter(
            id = "P17",
            name = "Battery Voltage",
            address = 0x00001C,
            length = 1,
            expression = "x*8/100",
            unit = Unit.VOLTS
        ),

        // Optional additional parameters
        SsmParameter(
            id = "P7",
            name = "MAP",
            address = 0x00000D,
            length = 1,
            expression = "x",
            unit = Unit.KPA
        ),
        SsmParameter(
            id = "P9",
            name = "Vehicle Speed",
            address = 0x000010,
            length = 1,
            expression = "x",
            unit = Unit.KMH
        ),
        SsmParameter(
            id = "P12",
            name = "Mass Airflow",
            address = 0x000013,
            length = 2,
            expression = "x/100",
            unit = Unit.GRAMS_PER_SEC
        )
    )
}
