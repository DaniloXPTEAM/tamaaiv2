package com.openminis.app.debug
object LLMRequestLog {
    class Entry(val provider: String, val requestURL: String, val requestHeaders: Map<String,String>,
        val requestBody: String, val durationMs: Long, val responseStatusCode: Int, val responseBody: String = "")
    fun add(entry: Entry) {}
}
