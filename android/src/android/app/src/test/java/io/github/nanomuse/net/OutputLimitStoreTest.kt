package io.github.nanomuse.net

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito

class OutputLimitStoreTest {
    private class Fixture {
        val values = mutableMapOf<String, String>()
        val preferences = Mockito.mock(SharedPreferences::class.java)
        private val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        val store = OutputLimitStore(preferences)

        init {
            Mockito.`when`(preferences.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] }
            Mockito.`when`(preferences.edit()).thenReturn(editor)
            Mockito.`when`(editor.putString(anyString(), anyString())).thenAnswer {
                values[it.getArgument<String>(0)] = it.getArgument(1)
                editor
            }
        }
    }

    @Test fun `accepted limit is private and isolated by model and route`() {
        val f = Fixture()
        val time = 2_000_000L
        f.store.save("endpoint-a", "model-x", 4096, time)
        val reloaded = OutputLimitStore(f.preferences)
        assertEquals(4096, reloaded.load("endpoint-a", "model-x", time))
        assertNull(reloaded.load("endpoint-b", "model-x", time))
        assertNull(reloaded.load("endpoint-a", "model-y", time))
        assertFalse(f.values.keys.single().contains("endpoint-a"))
        assertFalse(f.values.values.single().contains("model-x"))
        assertEquals(4096, reloaded.load("endpoint-a", "model-x", time + OutputLimitStore.MAX_AGE_MS))
        assertNull(reloaded.load("endpoint-a", "model-x", time + OutputLimitStore.MAX_AGE_MS + 1))
        assertNull(reloaded.load("endpoint-a", "model-x", time - 1))
    }

    @Test fun `invalid or ambiguous stored values do not cap requests`() {
        val f = Fixture()
        f.store.save("a", "bc", 2048, 1000)
        assertNull(f.store.load("ab", "c", 1000))
        val key = f.values.keys.single()
        for (bad in listOf("not json", "{}", "{\"limit\":0,\"at\":1000}", "{\"limit\":4096,\"at\":-1}")) {
            f.values[key] = bad
            assertNull(f.store.load("a", "bc", 1000))
        }
    }
}
