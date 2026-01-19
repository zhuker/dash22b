package com.example.dash22b.di

import android.content.Context
import com.example.dash22b.data.AndroidAssetLoader
import com.example.dash22b.data.AssetLoader
import com.example.dash22b.data.ParameterRegistry
import com.example.dash22b.data.PresetManager
import com.example.dash22b.data.PresetRepository
import com.example.dash22b.data.TpmsRepository
import com.example.dash22b.obd.SsmEcuInit

/**
 * Application-scoped dependency container.
 * Provides singleton instances of shared dependencies.
 */
class AppContainer(context: Context) {

    val assetLoader: AssetLoader = AndroidAssetLoader(context)

    // TODO: Replace hardcoded SSM parameters with XML parsing when serial cable is connected
    // When ECU connection is established:
    // 1. Get actual ECU init response from SsmSerialManager.sendInit()
    // 2. Create SsmEcuInit from the response bytes
    // 3. Use ParameterRegistry.fromXml() with the ecuInit for capability filtering
    //
    // Example future implementation:
    // fun initializeFromEcu(ecuInitBytes: ByteArray) {
    //     val ecuInit = SsmEcuInit(ecuInitBytes)
    //     val xmlStream = assetLoader.open("logger_METRIC_EN_v370.xml")
    //     parameterRegistry = ParameterRegistry.fromXml(xmlStream, ecuInit)
    // }
    val parameterRegistry: ParameterRegistry by lazy {
        // TODO: Replace with XML parsing when ECU is connected
        // For now, use hardcoded parameters for offline development
        ParameterRegistry.fromHardcodedSsm()
    }

    val tpmsRepository: TpmsRepository by lazy {
        TpmsRepository()
    }

    val presetRepository: PresetRepository by lazy {
        PresetRepository(context)
    }

    val presetManager: PresetManager by lazy {
        PresetManager(presetRepository)
    }
}
