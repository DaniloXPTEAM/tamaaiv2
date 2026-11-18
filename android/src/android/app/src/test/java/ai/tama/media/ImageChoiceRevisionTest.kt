package ai.tama.media

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.pet.PetPrefsContext
import org.junit.Assert.*
import org.junit.Test

/** The real image preference writers must invalidate old observations, even on ABA. */
class ImageChoiceRevisionTest {
    @Test fun `saving and clearing choices advance the revision and retain preference behavior`() {
        val context = PetPrefsContext()
        val prefs = context.getSharedPreferences("nanomuse", Context.MODE_PRIVATE)
        val before = ImageGen.imageChoiceRevision()
        ImageGen.save(context, "fixture", " first ")
        val first = ImageGen.imageChoiceRevision()
        assertEquals("fixture", prefs.getString("avatar.provider_id", null))
        assertEquals("first", prefs.getString("avatar.model", null))
        assertTrue(ImageGen.isChosen(context))
        ImageGen.save(context, "fixture", "other")
        val second = ImageGen.imageChoiceRevision()
        ImageGen.save(context, "fixture", "first")
        val third = ImageGen.imageChoiceRevision()
        ImageGen.clear(context)
        val cleared = ImageGen.imageChoiceRevision()
        assertTrue(before < first && first < second && second < third && third < cleared)
        assertFalse(ImageGen.isChosen(context))
        assertNull(prefs.getString("avatar.provider_id", null))
        assertNull(prefs.getString("avatar.model", null))
    }

    @Test fun `a failed preference write still invalidates an earlier observation`() {
        val broken = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("fixture preference failure")
        }
        val before = ImageGen.imageChoiceRevision()
        assertTrue(runCatching { ImageGen.save(broken, "fixture", "first") }.isFailure)
        assertTrue(ImageGen.imageChoiceRevision() > before)
    }
}
