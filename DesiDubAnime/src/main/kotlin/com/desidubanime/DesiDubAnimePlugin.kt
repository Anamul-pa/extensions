package com.desidubanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DesiDubAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DesiDubAnimeProvider())
    }
}
