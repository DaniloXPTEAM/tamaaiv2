import android.content.Context
import android.util.Log
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.EnvVarRepository
import java.nio.file.Files

fun main() {
    val root = Files.createTempDirectory("envlog-host").toFile()
    try {
        val parallel = root.resolve("parallel").apply { mkdirs() }
        BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        val repo = EnvVarRepository(Context(parallel))
        check(repo.add("FIXTURE_PRIVATE_NAME", "fixture-private-value", "fixture-private-note"))
        val id = repo.entries.value.single().id
        check(repo.update(id, "FIXTURE_NEW_NAME", "fixture-private-value", "fixture-private-note"))
        check(repo.getValue("FIXTURE_NEW_NAME") == "fixture-private-value")
        val metadata = parallel.resolve("env-vars.json").readText()
        check(metadata.contains("FIXTURE_NEW_NAME") && metadata.contains("fixture-private-note"))
        check(!metadata.contains("fixture-private-value"))
        repo.delete(id)
        val badRoot = root.resolve("fixture-private-path").apply { writeText("not a directory") }
        val bad = EnvVarRepository(Context(badRoot))
        check(bad.add("PRIVATE_FAIL_NAME", "fixture-private-value"))
        val brokenDir = root.resolve("broken").apply { mkdirs(); resolve("env-vars.json").writeText("bad") }
        EnvVarRepository(Context(brokenDir))
        check(Log.messages.size == 6)
        check(Log.messages.none { it.contains("FIXTURE_") || it.contains("fixture-private") || it.contains("PRIVATE_FAIL") })
        val safe = Log.messages.toList()
        Log.messages.clear()
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val normalDir = root.resolve("normal").apply { mkdirs() }
        val normal = EnvVarRepository(Context(normalDir))
        check(normal.add("FIXTURE_NAME", "v"))
        check(Log.messages.single().contains("FIXTURE_NAME"))
        println("PASS: real EnvVarRepository with host stubs, 6 selected parallel logs fixed; normal detail retained")
        println("Parallel log messages: " + safe.joinToString("; "))
        println("Metadata still contains variable names and notes; values not included by this test's stub")
    } finally { root.deleteRecursively() }
}
