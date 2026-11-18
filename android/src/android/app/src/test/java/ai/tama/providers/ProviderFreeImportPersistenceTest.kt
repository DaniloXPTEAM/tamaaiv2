package ai.tama.providers

import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderFreeImportPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private fun instance(id: String, selected: Boolean = false) = ProviderInstance(
        id = id, label = id, providerType = ProviderType.openRouter,
        credentialType = ProviderCredential.apiKey, tamaImportFreeOnly = selected,
        createdAt = 1_700_000_000_000L,
    )

    @Test fun `Room KV and JSON round trip independent choices while retaining groups`() {
        val entry = ModelEntry("one", LLMModel("vendor/a:free", "A", "OpenRouter"), uuid = "old-id")
        val config = ProviderConfig(
            instances = mutableListOf(instance("one", true), instance("two")),
            modelEntries = mutableListOf(entry),
            modelGroups = mutableListOf(ModelGroup(id = "group", name = "Group", memberEntryIds = mutableListOf(entry.id))),
            agentLoopModelEntryIds = mutableListOf(entry.id),
        )
        val snapshot = config.toSnapshot(json)
        assertEquals("1", snapshot.meta.single { it.key == ProviderConfigMetaKeys.TAMA_FREE_IMPORT_PREFIX + "one" }.value)
        assertFalse(snapshot.meta.any { it.key == ProviderConfigMetaKeys.TAMA_FREE_IMPORT_PREFIX + "two" })
        val restored = snapshot.toProviderConfig(json)
        assertTrue(restored.instances.first { it.id == "one" }.tamaImportFreeOnly)
        assertFalse(restored.instances.first { it.id == "two" }.tamaImportFreeOnly)
        assertEquals(restored.modelEntries.single().id, restored.modelGroups.single().memberEntryIds.single())
        assertEquals(restored.modelEntries.single().id, restored.agentLoopModelEntryIds.single())
        val backup = json.encodeToString(config)
        val decoded = json.decodeFromString<ProviderConfig>(backup)
        assertTrue(decoded.instances.first { it.id == "one" }.tamaImportFreeOnly)
        assertFalse(decoded.instances.first { it.id == "two" }.tamaImportFreeOnly)
    }

    @Test fun `old JSON and old Room snapshots default to the full import mode`() {
        val old = json.encodeToString(instance("old"))
        assertFalse(old.contains("tamaImportFreeOnly"))
        assertFalse(json.decodeFromString<ProviderInstance>(old).tamaImportFreeOnly)
        val snapshot = ProviderConfig(instances = mutableListOf(instance("old"))).toSnapshot(json)
        assertFalse(snapshot.toProviderConfig(json).instances.single().tamaImportFreeOnly)
    }
}
