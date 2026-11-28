package com.flaxfer

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FlaxferPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FlaxferProvider())
    }
}
