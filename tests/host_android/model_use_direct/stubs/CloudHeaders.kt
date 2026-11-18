package io.github.nanomuse.cloud
object CloudHeaders {
    fun isCloud(extraHeaders: Map<String, String>): Boolean = false
    fun forCall(id: String, messages: List<com.openminis.app.data.model.LLMMessage>): Map<String, String> = emptyMap()
    fun noteResponse(response: okhttp3.Response) {}
}
object NmStreamStatus { fun note(status: org.json.JSONObject) {} }
