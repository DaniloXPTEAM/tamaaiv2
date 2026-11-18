package com.openminis.app.offload
import com.openminis.app.data.model.*
import org.json.JSONObject
object ModelUseManager { fun entryDict(entry:ModelEntry, instance:ProviderInstance?): JSONObject = JSONObject().put("id", entry.id) }
