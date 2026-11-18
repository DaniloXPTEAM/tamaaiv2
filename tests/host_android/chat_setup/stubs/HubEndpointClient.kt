package io.github.nanomuse.hub

import org.json.JSONObject

/** The endpoint's tests never connect; Android uses the real HubClient and IncomingCall. */
@Suppress("UNUSED_PARAMETER")
class HubClient(
    url: String, key: String, hello: () -> JSONObject,
    onCall: (IncomingCall) -> Unit, onDevices: (List<Any>) -> Unit,
    onState: (Boolean, String) -> Unit,
)

@Suppress("UNUSED_PARAMETER")
class IncomingCall internal constructor(
    val id: String, val from: JSONObject, val action: String, val args: JSONObject, private val client: HubClient,
) {
    constructor(id: String, sender: String, args: JSONObject = JSONObject()) : this(
        id, JSONObject().put("id", sender).put("name", sender).put("kind", "web"), "task", args,
        HubClient("", "", { JSONObject() }, {}, {}, { _, _ -> }),
    )

    val senderName: String get() = from.optString("name").ifBlank { from.optString("id").ifBlank { "a device" } }
    val senderKind: String get() = from.optString("kind")
    val events = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    fun event(body: JSONObject) { events.add(body) }
}

internal object HubActions {
    class Refused(val code: String, message: String) : Exception(message)
}
