package com.example.dash22b.data

import android.content.Context
import java.io.InputStream

interface AssetLoader {
    fun open(fileName: String): InputStream
}

class AndroidAssetLoader(private val context: Context) : AssetLoader {
    override fun open(fileName: String): InputStream {
        return context.assets.open(fileName)
    }
}
