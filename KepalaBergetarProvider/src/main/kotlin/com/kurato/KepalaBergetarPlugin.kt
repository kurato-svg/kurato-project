package com.kurato

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KepalaBergetarPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(KepalaBergetarProvider())
    }
}
