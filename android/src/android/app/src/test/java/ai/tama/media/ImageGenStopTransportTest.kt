package ai.tama.media

import ai.tama.pricing.ModelCostGuard
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.pet.PetPrefsContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** ImageGen and the actual OpenAI image transport, with a local server and no provider account. */
class ImageGenStopTransportTest {
    private lateinit var context: Context

    @Before fun setUp() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val app = Mockito.mock(MinisApp::class.java)
        val preferences = PetPrefsContext()
        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = app
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getSharedPreferences(name, mode)
        }
        ModelCostGuard.setAllowedForCurrentProcess(true)
    }

    @After fun tearDown() { ModelCostGuard.setAllowedForCurrentProcess(false) }

    @Test fun `generation and editing preserve Stop at the POST and download boundaries`() = runBlocking {
        for (editing in listOf(false, true)) {
            for (stopAt in 1..2) {
                val server = MockWebServer()
                server.start()
                try {
                    val instance = ProviderInstance(
                        id = UUID.randomUUID().toString(), label = "Fixture", providerType = ProviderType.openAI,
                        credentialType = ProviderCredential.apiKey, customBaseURL = server.url("/v1").toString(),
                    )
                    val endpoint = ImageGen.Endpoint(instance, "fixture-key", "fixture-image")
                    if (stopAt == 2) {
                        server.enqueue(MockResponse().setBody("""{"data":[{"url":"${server.url("/image")}"}]}"""))
                        server.enqueue(MockResponse().setBody("unused"))
                    }
                    var checks = 0
                    val stop: () -> Unit = { if (++checks == stopAt) throw ImageAttemptTracker.Stopped() }
                    val failure = try {
                        if (editing) ImageGen.edit(context, endpoint, Mockito.mock(Bitmap::class.java), "fixture", false, stop)
                        else ImageGen.generate(context, endpoint, "fixture", "1024x1024", false, stop)
                        null
                    } catch (e: Exception) {
                        e
                    }
                    assertTrue("Stop must not be wrapped as a generic image failure", failure is ImageAttemptTracker.Stopped)
                    assertEquals(stopAt, checks)
                    assertEquals(stopAt - 1, server.requestCount)
                } finally {
                    server.shutdown()
                }
            }
        }
    }
}
