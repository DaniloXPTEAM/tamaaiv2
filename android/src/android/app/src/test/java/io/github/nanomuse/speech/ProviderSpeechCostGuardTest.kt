package io.github.nanomuse.speech

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.speech.ProviderSpeechRecognitionEngine
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SpeechRecognitionEngine
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

/** No microphone, Android audio service, or provider is used by this preflight test. */
class ProviderSpeechCostGuardTest {
    @After fun reset() { ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(false) }

    @Test fun `parallel cost refusal happens before provider lookup or recording`() {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getString(R.string.nm_model_cost_blocked)).thenReturn("Cost not verified")
        ai.tama.pricing.ModelCostGuard.setAllowedForCurrentProcess(false)
        var error: RecognitionError? = null
        var message: String? = null
        var ready = false
        val listener = object : SpeechRecognitionEngine.Listener {
            override fun onPartial(text: String) = Unit
            override fun onFinal(text: String) = Unit
            override fun onError(value: RecognitionError, text: String?) { error = value; message = text }
            override fun onReadyForSpeech() { ready = true }
        }
        ProviderSpeechRecognitionEngine(context).start(Locale.US, listener)
        if (BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            assertEquals(RecognitionError.UNKNOWN, error)
            assertEquals("Cost not verified", message)
            Mockito.verify(context, Mockito.never()).applicationContext
        } else {
            assertEquals(RecognitionError.OEM_NO_SERVICE, error)
        }
        assertEquals(false, ready)
        assertNull(error?.takeIf { it == RecognitionError.RECOGNIZER_BUSY })
    }
}
