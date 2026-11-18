package io.github.nanomuse.goals

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * `minis-global/nanomuse/goals.json` — every goal, newest first. Small file, read once, written
 * whole on every change; the flow is what the Goals tab and the goal cards observe.
 */
class GoalStore internal constructor(private val file: File) {
    private val _goals = MutableStateFlow(load())
    val goals: StateFlow<List<Goal>> = _goals

    fun all(): List<Goal> = _goals.value

    /** The file changed under the store — another account's set is in place (contract C12). */
    @Synchronized
    fun reload() {
        _goals.value = load()
    }

    fun get(id: String): Goal? = _goals.value.firstOrNull { it.id == id }

    fun forSession(sessionId: String): Goal? = _goals.value.firstOrNull { it.sessionId == sessionId }

    /** Publish a change only after its file replacement succeeds. */
    @Synchronized
    fun upsert(goal: Goal): Boolean {
        val stamped = goal.copy(updatedAt = System.currentTimeMillis())
        val rest = _goals.value.filterNot { it.id == goal.id }
        val next = (listOf(stamped) + rest).sortedByDescending { it.createdAt }
        if (!save(next)) return false
        _goals.value = next
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val next = _goals.value.filterNot { it.id == id }
        if (!save(next)) return false
        _goals.value = next
        return true
    }

    /** A `nanomuse-goal-update` block from the goal's session. */
    @Synchronized
    fun applyUpdate(goalId: String, progress: Int?, status: String?, note: String?): Boolean {
        val goal = get(goalId) ?: return false
        val newStatus = when (status?.lowercase()) {
            "done", "completed" -> GoalStatus.DONE
            "paused" -> GoalStatus.PAUSED
            else -> if (goal.status == GoalStatus.DONE) GoalStatus.ACTIVE else goal.status
        }
        return upsert(
            goal.copy(
                progress = (progress ?: goal.progress).coerceIn(0, 100),
                status = newStatus,
                lastNote = note?.takeIf { it.isNotBlank() } ?: goal.lastNote,
                lastCheckedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun load(): List<Goal> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(Goal.fromJson(it)) } }
        }.onFailure { AppLogger.warning(TAG, "goals.json unreadable: ${it.message}") }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    private fun save(goals: List<Goal>): Boolean = runCatching {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) error("goal directory is unavailable")
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { goals.forEach { put(it.toJson()) } }.toString(2))
        // Both files are in the same directory. Never truncate the previous goals when a
        // replacement fails; the UI and the Pet can retry the change on the next attempt.
        if (!tmp.renameTo(file)) error("could not replace goals.json")
    }.onFailure { AppLogger.warning(TAG, "goals.json write failed: ${it.message}") }.isSuccess

    companion object {
        private const val TAG = "Goals"

        @Volatile private var instance: GoalStore? = null

        fun get(context: Context): GoalStore =
            instance ?: synchronized(this) {
                instance ?: GoalStore(File(context.applicationContext.filesDir, "minis-global/nanomuse/goals.json"))
                    .also { instance = it }
            }
    }
}
