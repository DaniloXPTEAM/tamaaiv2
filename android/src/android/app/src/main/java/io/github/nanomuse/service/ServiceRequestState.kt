package io.github.nanomuse.service

import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A rejected request is known; the absence of a rejection does not prove the service is alive. */
internal enum class ServiceRequestIssue { START, STOP }

/** Record only the newest request's outcome, even if concurrent Android calls finish out of order. */
internal class ServiceRequestState {
    private val lock = Any()
    private var sequence = 0L
    private val _issue = MutableStateFlow<ServiceRequestIssue?>(null)
    val issue: StateFlow<ServiceRequestIssue?> = _issue.asStateFlow()

    fun attempt(kind: ServiceRequestIssue, request: () -> Unit): Boolean {
        val current = synchronized(lock) { ++sequence }
        return try {
            request()
            finish(current, null)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            finish(current, kind)
            false
        }
    }

    private fun finish(current: Long, value: ServiceRequestIssue?) {
        synchronized(lock) {
            if (sequence == current) _issue.value = value
        }
    }
}
