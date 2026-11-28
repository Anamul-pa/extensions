package com.novahd

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NovaHdPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NovaHdProvider())
    }
}
