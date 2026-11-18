package ai.tama.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCommandTest {
    @Test fun `exact commands open locally with either slash and accent`() {
        listOf("/diagnostico", " /DIAGNOSTICO ", "/diagnóstico", "／Diagnóstico", "／diagnostico")
            .forEach { assertTrue(DiagnosticCommand.matches(it)) }
    }

    @Test fun `ordinary conversation and incomplete commands never match`() {
        listOf("", "/", "diagnostico", "/diagnost", "/diagnosticos", "/diagnostico extra",
            "see /diagnostico", "/ diagnóstico", "/diagnostico\nextra", "/prompt-size")
            .forEach { assertFalse(DiagnosticCommand.matches(it)) }
    }
}
