package com.novahd

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NovaHdPlugin : BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(NovaHdProvider())
    }
}
