package ai.tama.media

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import ai.tama.router.AutoModelStore
import ai.tama.router.Need
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import io.github.nanomuse.pet.PetPrefsContext
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito

/** Production selection and consent store, with an in-memory repository and preferences. No network. */
class AutoImageSelectionTest {
    private fun fixture(): Pair<Context, AutoModelStore.Identity> {
        val preferences = PetPrefsContext()
        val app = Mockito.mock(MinisApp::class.java)
        val repo = Mockito.mock(ProviderRepository::class.java)
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = app
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getSharedPreferences(name, mode)
        }
        val instance = ProviderInstance(
            UUID.randomUUID().toString(), "Fixture", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "https://fixture.invalid/v1",
        )
        val model = LLMModel("image-model", "Fixture image", "fixture", outputModalities = listOf("image"))
        val entry = ModelEntry(instance.id, model)
        val config = ProviderConfig(instances = mutableListOf(instance), modelEntries = mutableListOf(entry))
        Mockito.`when`(app.providerRepositoryOrNull).thenReturn(repo)
        Mockito.`when`(repo.config).thenReturn(MutableStateFlow(config))
        Mockito.`when`(repo.usableApiKey(instance)).thenReturn("fixture-key")
        val identity = AutoModelStore.identity(entry, instance)
        AutoModelStore.setEnabled(context, true)
        AutoModelStore.setAllowUnverifiedAutomaticCost(context, true)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        AutoModelStore.setImageApproved(context, identity, true)
        return context to identity
    }

    @Test fun `parallel selection does not survive consent capability or account ABA`() {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val (context, identity) = fixture()
        val first = requireNotNull(AutoImageSelection.choose(context))
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(context, first))
        AutoModelStore.setImageApproved(context, identity, false)
        AutoModelStore.setImageApproved(context, identity, true)
        assertNull(AutoImageSelection.endpointIfStillAllowed(context, first))

        val second = requireNotNull(AutoImageSelection.choose(context))
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, false)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        assertNull(AutoImageSelection.endpointIfStillAllowed(context, second))

        val third = requireNotNull(AutoImageSelection.choose(context))
        AutoModelStore.accountChanged(context, identity.providerId)
        AutoModelStore.setCapability(context, identity, Need.IMAGE_GENERATION, true)
        AutoModelStore.setImageApproved(context, identity, true)
        assertNull(AutoImageSelection.endpointIfStillAllowed(context, third))
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(context, requireNotNull(AutoImageSelection.choose(context))))
    }

    @Test fun `normal selection keeps its previous revalidation behavior`() {
        assumeTrue(BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test")
        val (context, identity) = fixture()
        val selection = requireNotNull(AutoImageSelection.choose(context))
        AutoModelStore.setImageApproved(context, identity, false)
        AutoModelStore.setImageApproved(context, identity, true)
        assertNotNull(AutoImageSelection.endpointIfStillAllowed(context, selection))
    }
}
