package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import kotlin.system.exitProcess
import org.junit.runner.JUnitCore

/** Runs the pre-existing socket tests with one host-only package identity. */
object HubClientIdentityRunner {
    @JvmStatic fun main(args: Array<String>) {
        val identity = args.single()
        BuildConfig.APPLICATION_ID = when (identity) {
            "parallel" -> "ai.tama.assistant.tamav2test"
            "normal" -> "ai.tama.assistant"
            else -> error("Expected parallel or normal")
        }
        val result = JUnitCore.runClasses(RemoteTaskSocketTest::class.java)
        result.failures.forEach { System.err.println(it.toString() + "\n" + it.trace) }
        println("Existing RemoteTaskSocketTest ($identity): ${result.runCount} run, ${result.failureCount} failures")
        if (!result.wasSuccessful()) exitProcess(1)
    }
}
