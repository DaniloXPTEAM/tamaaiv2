package io.github.nanomuse.router

/** A profile governs what is sent, not what the risk and offload gates may execute. */
enum class Profile { LEAN, BASIC, FULL }

/** Intent hints are descriptive: a hint by itself never executes an action. */
enum class ToolGroup {
    WEB, WEB_ADVANCED, FILES, SHELL, MEMORY, ANDROID, SCREEN, IMAGE, CONFIG, SCHEDULE, MEDIA,
}
