package com.example.dash22b.di

import android.content.Context
import com.example.dash22b.data.AndroidAssetLoader
import com.example.dash22b.data.AssetLoader
import com.example.dash22b.data.ParameterRegistry
import com.example.dash22b.data.TpmsRepository

/**
 * Application-scoped dependency container.
 * Provides singleton instances of shared dependencies.
 */
class AppContainer(context: Context) {

    val assetLoader: AssetLoader = AndroidAssetLoader(context)

    val parameterRegistry: ParameterRegistry by lazy {
        ParameterRegistry.fromHardcodedSsm()
    }

    val tpmsRepository: TpmsRepository by lazy {
        TpmsRepository()
    }
}
