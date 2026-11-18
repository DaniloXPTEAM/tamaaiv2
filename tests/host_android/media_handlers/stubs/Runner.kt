import org.junit.runner.JUnitCore
fun main(args:Array<String>) {
 if (args.firstOrNull()=="normal") com.openminis.app.BuildConfig.APPLICATION_ID="ai.tama.assistant"
 val result = JUnitCore.runClasses(ai.tama.media.MediaOffloadImageTest::class.java, ai.tama.media.MediaOffloadVideoTest::class.java)
 result.failures.forEach { System.err.println(it.trace) }
 println("identity=${com.openminis.app.BuildConfig.APPLICATION_ID} run=${result.runCount} ignored=${result.ignoreCount} assumptions=${result.assumptionFailureCount} failures=${result.failureCount}")
 check(result.wasSuccessful())
}
