package com.openminis.app.logging

/** The host test discards logs; Android uses the real logger. */
object AppLogger {
    fun info(tag: String, message: String) = Unit
    fun warning(tag: String, message: String) = Unit
}
