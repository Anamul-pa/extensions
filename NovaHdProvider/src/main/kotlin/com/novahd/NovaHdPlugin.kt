package com.novahd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NovaHdPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NovaHdProvider())
    }
}
