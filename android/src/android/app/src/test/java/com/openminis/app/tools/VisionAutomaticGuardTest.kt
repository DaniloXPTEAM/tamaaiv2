package com.openminis.app.tools

import ai.tama.router.AutoModelStore
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import io.github.nanomuse.pet.PetPrefsContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class VisionAutomaticGuardTest {
    @Test fun automaticVisionStopsBeforeResolvingAnyProvider() = runBlocking {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val context = PetPrefsContext()
        AutoModelStore.setEnabled(context, true)
        val repo = mock(ProviderRepository::class.java)
        val result = VisionGroupResolver.describe(repo, context, byteArrayOf(1), "image/png")
        assertTrue(result is VisionGroupResolver.VisionResult.Failure)
        assertTrue((result as VisionGroupResolver.VisionResult.Failure).reason.contains("do not retry"))
        verifyNoInteractions(repo)
    }

    @Test fun missingContextAlsoFailsClosedInParallel() = runBlocking {
        assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        val repo = mock(ProviderRepository::class.java)
        val result = VisionGroupResolver.describe(repo, null, byteArrayOf(1), "image/png")
        assertTrue(result is VisionGroupResolver.VisionResult.Failure)
        verifyNoInteractions(repo)
    }
}
