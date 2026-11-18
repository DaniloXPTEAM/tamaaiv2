package io.github.nanomuse.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking

class BrowserGuardTest {
    private fun target(
        type: String = "", name: String = "", id: String = "", placeholder: String = "", autocomplete: String = "",
        inputMode: String = "", aria: String = "", label: String = "", text: String = "", maxLength: Int = 0,
        host: String = "shop.example.com", buttonAria: String = "",
    ) = BrowserGuard.Target(true, "INPUT", type, name, id, placeholder, autocomplete, inputMode, aria, label, text, "", maxLength, "https://$host/x", host, buttonAria)

    @Test fun `password and code fields are refused`() {
        assertTrue(BrowserGuard.judgeType(target(type = "password")) is BrowserGuard.Verdict.Refuse)
        assertTrue(BrowserGuard.judgeType(target(autocomplete = "one-time-code")) is BrowserGuard.Verdict.Refuse)
        assertTrue(BrowserGuard.judgeType(target(placeholder = "请输入验证码")) is BrowserGuard.Verdict.Refuse)
        assertTrue(BrowserGuard.judgeType(target(label = "SMS code", inputMode = "numeric", maxLength = 6)) is BrowserGuard.Verdict.Refuse)
        assertTrue(BrowserGuard.judgeType(target(name = "user_pwd")) is BrowserGuard.Verdict.Refuse)
        val r = BrowserGuard.judgeType(target(type = "password")) as BrowserGuard.Verdict.Refuse
        assertTrue(r.text.startsWith(BrowserGuard.HANDOFF_PREFIX))
    }

    @Test fun `Portuguese and HTML card fields hand off instead of being typed`() {
        val fields = listOf(
            target(placeholder = "Senha"),
            target(label = "Código de verificação"),
            target(name = "numero_cartao"),
            target(autocomplete = "section-billing cc-number"),
            target(placeholder = "Código", inputMode = "numeric", maxLength = 6),
        )
        for (field in fields) assertTrue(BrowserGuard.judgeType(field) is BrowserGuard.Verdict.Refuse)
        val card = BrowserGuard.judgeType(target(autocomplete = "cc-number")) as BrowserGuard.Verdict.Refuse
        assertTrue(card.text.contains("card detail"))
        assertTrue(card.text.contains("Do not try another selector or JavaScript"))
        assertTrue(!card.text.contains("has been brought to the front"))
        assertTrue(BrowserGuard.judgeType(target(label = "CEP", inputMode = "numeric", maxLength = 8)) is BrowserGuard.Verdict.Proceed)
    }

    @Test fun `uninspectable browser targets are refused and cancellation propagates`() = runBlocking {
        for (typing in listOf(false, true)) {
            for (raw in listOf("null", "not-json", """{"found":false}""")) {
                val result = BrowserGuard.inspect(typing) { raw }
                assertTrue(result.verdict is BrowserGuard.Verdict.Refuse)
            }
            val thrown = BrowserGuard.inspect(typing) { throw IllegalStateException("webview unavailable") }
            assertTrue(thrown.verdict is BrowserGuard.Verdict.Refuse)
            assertTrue((thrown.verdict as BrowserGuard.Verdict.Refuse).text.contains("not performed"))
        }
        val foundClick = BrowserGuard.inspect(false) { """{"found":true,"text":"Enviar"}""" }
        assertTrue(foundClick.verdict is BrowserGuard.Verdict.Ask)
        val foundType = BrowserGuard.inspect(true) { """{"found":true,"label":"Senha"}""" }
        assertTrue(foundType.verdict is BrowserGuard.Verdict.Refuse)
        val ordinary = BrowserGuard.inspect(false) { """{"found":true,"text":"Pesquisar"}""" }
        assertTrue(ordinary.verdict is BrowserGuard.Verdict.Proceed)
        var cancelled = false
        try {
            BrowserGuard.inspect(true) { throw CancellationException("stop") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun `ordinary fields are typed into`() {
        assertTrue(BrowserGuard.judgeType(target(type = "text", name = "q", placeholder = "Search")) is BrowserGuard.Verdict.Proceed)
        assertTrue(BrowserGuard.judgeType(target(type = "email", name = "email")) is BrowserGuard.Verdict.Proceed)
        assertTrue(BrowserGuard.judgeType(target(type = "text", name = "pinyin")) is BrowserGuard.Verdict.Proceed)
        assertTrue(BrowserGuard.judgeType(target(type = "text", label = "邮编", inputMode = "numeric", maxLength = 6)) is BrowserGuard.Verdict.Proceed)
    }

    @Test fun `pay send delete taps ask with the host as target`() {
        val pay = BrowserGuard.judgeClick(target(text = "确认支付 ¥38.00")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.MONEY, pay.assessment.riskClass)
        assertEquals("shop.example.com", pay.assessment.target)
        val send = BrowserGuard.judgeClick(target(text = "发送", host = "mail.example.com")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.OUTBOUND, send.assessment.riskClass)
        val del = BrowserGuard.judgeClick(target(aria = "Delete conversation")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.DESTRUCTIVE, del.assessment.riskClass)
        val order = BrowserGuard.judgeClick(target(text = "Place order")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.MONEY, order.assessment.riskClass)
    }

    @Test fun `Portuguese actions and the accessible label are checked before a click`() {
        val pay = BrowserGuard.judgeClick(target(text = "Avançar", aria = "Pagar R$ 20")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.MONEY, pay.assessment.riskClass)
        assertEquals("Pagar R$ 20", pay.elementText)
        assertEquals("shop.example.com", pay.assessment.target)
        val del = BrowserGuard.judgeClick(target(text = "Excluir", aria = "Confirmar pagamento")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.MONEY, del.assessment.riskClass)
        assertEquals("Confirmar pagamento", del.elementText)
        val send = BrowserGuard.judgeClick(target(text = "Enviar")) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.OUTBOUND, send.assessment.riskClass)
        val parent = BrowserGuard.Target.parse(
            """{"found":true,"text":"Continuar","aria":"Avançar","buttonAria":"Confirmar pagamento","host":"shop.example.com"}""",
        )!!
        val parentRisk = BrowserGuard.judgeClick(parent) as BrowserGuard.Verdict.Ask
        assertEquals(RiskClass.MONEY, parentRisk.assessment.riskClass)
        assertEquals("Confirmar pagamento", parentRisk.elementText)
        assertTrue(BrowserGuard.judgeClick(target(text = "Pesquisar", aria = "Buscar")) is BrowserGuard.Verdict.Proceed)
    }

    @Test fun `navigation taps proceed`() {
        for (t in listOf("下一页", "查看详情", "Search", "Log in", "Add to cart", "Open menu", "商品详情", "筛选"))
            assertTrue(t, BrowserGuard.judgeClick(target(text = t)) is BrowserGuard.Verdict.Proceed)
    }

    @Test fun `describe js embeds the selector safely`() {
        val js = BrowserGuard.describeJs("button[name=\"pay\"]", null, null)
        assertTrue(js.contains("document.querySelector(\"button[name=\\\"pay\\\"]\")"))
        assertTrue(js.contains("buttonAria: s(btn.getAttribute"))
        assertTrue(BrowserGuard.describeJs(null, 10, 20).contains("elementFromPoint(10, 20)"))
    }
}
