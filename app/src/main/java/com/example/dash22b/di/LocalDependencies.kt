package com.example.dash22b.di

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.dash22b.data.ParameterRegistry
import com.example.dash22b.data.TpmsRepository

/**
 * CompositionLocal providers for dependency injection into Composables.
 */
val LocalParameterRegistry = staticCompositionLocalOf<ParameterRegistry> {
    error("ParameterRegistry not provided. Wrap your content with CompositionLocalProvider.")
}

val LocalTpmsRepository = staticCompositionLocalOf<TpmsRepository> {
    error("TpmsRepository not provided. Wrap your content with CompositionLocalProvider.")
}
