package io.github.nanomuse.chat

/** The same memory-toggle guidance used at send time, without reading any personal data. */
object AgentMemoryPrompt {
    fun toolList(memoryOn: Boolean): String = if (memoryOn) {
            """
- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.
- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge."""
        } else {
            // Empty — no memory_write / memory_get bullets when disabled.
            // The "Memory system:" section below also collapses, so the
            // model gets a coherent picture rather than half-mentioned
            // tools it can't actually call.
            ""
        }

    fun guidance(memoryOn: Boolean): String = if (memoryOn) {
            """

Memory system (currently ENABLED):
- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.
- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.
- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.
- Use memory_get to recall past knowledge before starting tasks — check if there are relevant memories that can help.
- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.
- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.
- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.
- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later."""
        } else {
            """

Memory system (currently DISABLED):
- The user has turned OFF memory injection and memory tools for this session. GLOBAL.md and recent daily logs are NOT included in this prompt, and the memory_write / memory_get tools are NOT available — do not attempt to call them.
- If the user asks why earlier memories aren't visible, or asks you to save something, tell them memory is currently disabled and point them at the /memory slash command or [Settings → Memory](minis://settings/memory) to re-enable it.
- SOUL.md (personality / identity) is unaffected by this toggle; the persona section above still applies."""
        }
}
