package ai.tama.diagnostics

/** An exact local action, never a prefix match on ordinary conversation text. */
internal object DiagnosticCommand {
    fun matches(text: String): Boolean {
        val input = text.trim()
        if (input.length < 2 || (input[0] != '/' && input[0] != '／')) return false
        val name = input.substring(1)
        return name.equals("diagnostico", ignoreCase = true) || name.equals("diagnóstico", ignoreCase = true)
    }
}
