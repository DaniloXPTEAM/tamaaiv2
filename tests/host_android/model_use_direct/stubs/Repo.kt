package com.openminis.app.data.repository
import com.openminis.app.data.model.*
open class ProviderRepository(val entry: ModelEntry, val config: ProviderInstance) {
    var onEntries: () -> Unit = {}
    var onApiKey: () -> Unit = {}
    open fun resolvedAgentLoopEntries(): List<ModelEntry> { onEntries(); return listOf(entry) }
    open fun instance(id:String): ProviderInstance? = if (id == config.id) config else null
    open fun loadApiKey(id:String): String? { onApiKey(); return "fixture" }
    fun setImageEndpointResolved(instanceId:String, endpoint:ImageEndpointMode) {}
}
