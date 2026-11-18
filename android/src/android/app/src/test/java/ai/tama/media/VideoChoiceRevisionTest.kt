package ai.tama.media

import io.github.nanomuse.media.MediaModels
import io.github.nanomuse.pet.PetPrefsContext
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoChoiceRevisionTest {
    @Test fun `saving and clearing a choice invalidate earlier observations even after ABA`() {
        val context = PetPrefsContext()
        val before = MediaModels.videoChoiceRevision()
        MediaModels.saveVideo(context, "fixture", "first")
        val first = MediaModels.videoChoiceRevision()
        MediaModels.saveVideo(context, "fixture", "other")
        val second = MediaModels.videoChoiceRevision()
        MediaModels.saveVideo(context, "fixture", "first")
        val third = MediaModels.videoChoiceRevision()
        MediaModels.clearVideo(context)
        val cleared = MediaModels.videoChoiceRevision()
        assertTrue(before < first && first < second && second < third && third < cleared)
    }
}
