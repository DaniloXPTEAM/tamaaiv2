package io.github.nanomuse.hands

/** The screen capability paragraph, separated from device state for count-only host tests. */
object HandsCapabilityPrompt {
    data class State(
        val enabled: Boolean,
        val androidOk: Boolean,
        val serviceOn: Boolean,
        val overlayOk: Boolean,
        val modelLabel: String?,
        val deepLink: String,
    ) {
        val ready: Boolean get() = androidOk && serviceOn && overlayOk && modelLabel != null
    }

    fun build(state: State): String {
        return buildString {
            append("## Apps without an API — the ladder (TAMA AI)\n")
            append("Most everyday apps here (12306, 微信, 支付宝, 美团, 淘宝, 京东…) have no API. Climb in this order and say which rung you are on before you start: ")
            append("(1) a skill, a CLI or an MCP server; (2) the page fetched with the user's login or `browser_use`; (3) the phone's own screen, last. ")
            append("A task is rarely all screen: find the number with a command, the button with the screen, and ask before it is pressed.\n")
            if (state.enabled && state.ready) {
                append("The screen is available: `nanomuse-hands run --task \"<one clear task, with every detail the hands need>\" [--app \"<app name>\"]` ")
                append("(screen model: ${state.modelLabel}). It uses screenshots for perception; local risk checks can also read labels near taps. A capsule with Stop shows its actions. ")
                append("Never type passwords, codes or card numbers; ask the user to take over. Ask before paying, sending, posting or deleting. Automatic checks can miss a field or action, and an earlier approval may apply. ")
                append("It returns JSON: `outcome` is done (with `answer`), stopped (the user tapped Stop — do not restart it), needs_user (`question` — relay it and wait), infeasible or failed (with `message`); `last_screen` is a picture you can show with `![screen](<last_screen>)`. ")
                append("Keep the complete JSON result: do not pipe nanomuse-hands run through head or tail. Save it to a file or parse named fields with jq, preserving ok, outcome, steps, last_screen and the answer, message or question. ")
                append("Tell the user in one line that you are about to use the screen and which app, then run it; give one task per run, and never a payment as the task. `nanomuse-hands apps` lists the installed apps; `nanomuse-hands status` says what is set up.")
            } else if (state.enabled) {
                val missing = buildList {
                    if (!state.androidOk) add("Android 11 or newer")
                    if (!state.serviceOn) add("the accessibility service is off")
                    if (!state.overlayOk) add("no permission to draw over other apps")
                    if (state.modelLabel == null) add("no model that can see pictures")
                }.joinToString(", ")
                append("The screen is switched on but not usable yet ($missing). If a task needs it, say so in one line and give the link [Hands](${state.deepLink}) — it opens the setting; do not try android-a11y-cli instead.")
            } else {
                append("Operating the phone's screen is switched off (the default). If a task can only be done on the screen, say so in one line and give the link [Hands](${state.deepLink}) so the user can switch it on; do not try android-a11y-cli instead, and do not pretend.")
            }
        }
    }
}
