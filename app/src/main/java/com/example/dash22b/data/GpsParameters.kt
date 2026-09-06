package com.example.dash22b.data

/**
 * Parameters this app produces itself, rather than reading from the ECU.
 *
 * GPS speed is offered alongside SSM parameters so it can be put on a gauge or graphed
 * against Vehicle Speed -- the comparison that shows speedometer error, and the only
 * speed available at all when the ECU is not connected.
 *
 * Position is deliberately absent. A parameter value is a `Float`, which resolves latitude
 * to roughly a metre; position belongs in the GPS CSV, where it is a `Double`.
 */
object GpsParameters {

    /** Name used as the key in [EngineData.values], in presets, and as the CSV heading. */
    const val SPEED = "GPS Speed"

    val speed: ParameterDefinition = ParameterDefinitionImpl(
        id = "GPS_SPEED",
        type = "float",
        unit = DisplayUnit.KMH,
        name = SPEED,
        description = "Ground speed from the GNSS receiver. Doppler-derived, so it is " +
            "independent of tyre size and gearing, unlike the ECU's Vehicle Speed.",
        minExpected = 0f,
        maxExpected = 250f,
        accessportName = SPEED
    )

    /** Everything to add to the registry on top of the ECU's own parameters. */
    val all: List<ParameterDefinition> = listOf(speed)
}
