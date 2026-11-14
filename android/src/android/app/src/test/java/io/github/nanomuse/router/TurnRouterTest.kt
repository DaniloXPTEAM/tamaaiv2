package io.github.nanomuse.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRouterTest {
    private data class Case(val text: String, val profile: Profile, val group: ToolGroup? = null)

    @Test fun `portuguese and english intents including risks are deterministic`() {
        val cases = listOf(
            Case("oi", Profile.LEAN),
            Case("Olá, tudo bem?", Profile.LEAN),
            Case("obrigado", Profile.LEAN),
            Case("bom dia", Profile.LEAN),
            Case("hello", Profile.LEAN),
            Case("how are you?", Profile.LEAN),
            Case("explain recursion", Profile.LEAN),
            Case("quero conversar", Profile.LEAN),
            Case("PESQUISE sobre astronomia", Profile.BASIC, ToolGroup.WEB),
            Case("pesquisa a notícia de hoje", Profile.BASIC, ToolGroup.WEB),
            Case("busque na internet", Profile.BASIC, ToolGroup.WEB),
            Case("entre no site", Profile.BASIC, ToolGroup.WEB),
            Case("abre o navegador", Profile.BASIC, ToolGroup.WEB),
            Case("search the web", Profile.BASIC, ToolGroup.WEB),
            Case("browse this website", Profile.BASIC, ToolGroup.WEB),
            Case("look up the news", Profile.BASIC, ToolGroup.WEB),
            Case("baixe os cookies", Profile.FULL, ToolGroup.WEB_ADVANCED),
            Case("fazer login no site", Profile.FULL, ToolGroup.WEB_ADVANCED),
            Case("scroll the page", Profile.FULL, ToolGroup.WEB_ADVANCED),
            Case("escreva no arquivo", Profile.BASIC, ToolGroup.FILES),
            Case("edita a pasta do projeto", Profile.BASIC, ToolGroup.FILES),
            Case("analise este código", Profile.BASIC, ToolGroup.FILES),
            Case("instalar um script", Profile.BASIC, ToolGroup.FILES),
            Case("save the file", Profile.BASIC, ToolGroup.FILES),
            Case("edit this folder", Profile.BASIC, ToolGroup.FILES),
            Case("install the code", Profile.BASIC, ToolGroup.FILES),
            Case("run a shell command", Profile.BASIC, ToolGroup.SHELL),
            Case("lembra disso", Profile.BASIC, ToolGroup.MEMORY),
            Case("anota na memória", Profile.BASIC, ToolGroup.MEMORY),
            Case("remember this", Profile.BASIC, ToolGroup.MEMORY),
            Case("qual a bateria?", Profile.BASIC, ToolGroup.ANDROID),
            Case("clima em Recife", Profile.BASIC, ToolGroup.ANDROID),
            Case("alarme às 7", Profile.BASIC, ToolGroup.ANDROID),
            Case("abre o aplicativo", Profile.BASIC, ToolGroup.ANDROID),
            Case("weather tomorrow", Profile.BASIC, ToolGroup.ANDROID),
            Case("battery status", Profile.BASIC, ToolGroup.ANDROID),
            Case("clique e preenche o campo", Profile.FULL, ToolGroup.SCREEN),
            Case("toca na tela", Profile.FULL, ToolGroup.SCREEN),
            Case("tap the screen", Profile.FULL, ToolGroup.SCREEN),
            Case("crie uma imagem de gato", Profile.BASIC, ToolGroup.IMAGE),
            Case("draw a picture", Profile.BASIC, ToolGroup.IMAGE),
            Case("configure o provedor", Profile.BASIC, ToolGroup.CONFIG),
            Case("alterar o modelo", Profile.BASIC, ToolGroup.CONFIG),
            Case("configure my provider", Profile.BASIC, ToolGroup.CONFIG),
            Case("agenda para amanhã", Profile.BASIC, ToolGroup.SCHEDULE),
            Case("run this every day", Profile.BASIC, ToolGroup.SCHEDULE),
            Case("delete the file", Profile.FULL, ToolGroup.FILES),
            Case("apague o arquivo", Profile.FULL, ToolGroup.FILES),
            Case("envie uma mensagem", Profile.FULL),
            Case("pay the bill", Profile.FULL),
            Case("comprar algo", Profile.FULL),
            Case("faça em várias etapas", Profile.FULL),
            // Whole-word matching avoids an accidental tool route for a substring.
            Case("eu gosto de arquivosidade", Profile.LEAN),
        )
        assertTrue(cases.size >= 40)
        for (case in cases) {
            val result = TurnRouter.route(case.text)
            assertEquals(case.text, case.profile, result.profile)
            if (case.group != null) assertTrue(case.text, case.group in result.hints)
            assertTrue(case.text, Need.CHAT in result.needs)
            assertEquals(case.text, case.profile != Profile.LEAN, Need.TOOLS in result.needs)
        }
    }

    @Test fun `attachments, image generation, continuity and override retain intent`() {
        val image = TurnRouter.route("olhe", hasImageAttachment = true)
        assertTrue(Need.VISION in image.needs)
        assertFalse(Need.TOOLS in image.needs)
        val file = TurnRouter.route("analise", hasFileAttachment = true)
        assertEquals(Profile.BASIC, file.profile)
        assertTrue(ToolGroup.FILES in file.hints)
        assertTrue(Need.IMAGE_GENERATION in TurnRouter.route("gere uma imagem").needs)
        assertFalse(Need.IMAGE_GENERATION in TurnRouter.route("o que é uma imagem?").needs)
        val follow = TurnRouter.route("continue", SessionState(Profile.FULL, setOf(ToolGroup.WEB), true))
        assertEquals(Profile.FULL, follow.profile)
        assertTrue(ToolGroup.WEB in follow.hints)
        assertEquals(Profile.LEAN, TurnRouter.route("continue", SessionState(Profile.FULL, emptySet(), false)).profile)
        val manualLight = TurnRouter.route("pesquise", override = Profile.LEAN)
        assertEquals(Profile.LEAN, manualLight.profile)
        assertTrue(Need.TOOLS in manualLight.needs)
        assertEquals(Profile.FULL, TurnRouter.route("oi", override = Profile.FULL).profile)
    }

    @Test fun `budget uses three calls and preserves original needs`() {
        val costs = mapOf(Profile.LEAN to 400, Profile.BASIC to 3_400, Profile.FULL to 11_500)
        val requested = TurnRouter.route("pesquise um site")
        val limited = TurnRouter.fit(requested, ProviderBudget(tokensPerMinute = 8_000), costs)!!
        assertEquals(Profile.LEAN, limited.profile)
        assertEquals(Profile.BASIC, limited.requestedProfile)
        assertTrue(limited.budgetLimited)
        assertTrue(Need.TOOLS in limited.needs)
        assertEquals(requested, TurnRouter.fit(requested, ProviderBudget(), costs))
        assertEquals(Profile.BASIC, TurnRouter.fit(requested, ProviderBudget(10_200), costs)!!.profile)
        assertNull(TurnRouter.fit(requested, ProviderBudget(1_199), costs))
        assertNull(TurnRouter.fit(requested, ProviderBudget(0), costs))
        // A huge BASIC fragment must not be selected merely because FULL fits.
        val nonMonotonic = mapOf(Profile.LEAN to 200, Profile.BASIC to 4_000, Profile.FULL to 1_000)
        assertEquals(Profile.LEAN, TurnRouter.fit(requested, ProviderBudget(3_000), nonMonotonic)!!.profile)
        assertNull(TurnRouter.fit(TurnRouter.route("oi"), ProviderBudget(300), nonMonotonic))
        assertEquals(Profile.BASIC, TurnRouter.lower(Profile.FULL))
        assertEquals(Profile.LEAN, TurnRouter.lower(Profile.BASIC))
        assertNull(TurnRouter.lower(Profile.LEAN))
    }
}
