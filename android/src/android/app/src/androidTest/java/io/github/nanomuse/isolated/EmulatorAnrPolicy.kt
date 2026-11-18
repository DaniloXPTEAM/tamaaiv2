package io.github.nanomuse.isolated

/** Narrow recognition policy for a known system dialog in the disposable CI emulator only. */
internal object EmulatorAnrPolicy {
    private const val SYSTEM = "android"
    val titles = setOf(
        "Pixel Launcher isn't responding", "Pixel Launcher isn’t responding",
        "Messages isn't responding", "Messages isn’t responding",
    )

    fun matches(
        rootPackage: String?,
        titlePackage: String?,
        title: String?,
        buttonPackage: String?,
        button: String?,
    ): Boolean = rootPackage == SYSTEM && titlePackage == SYSTEM && buttonPackage == SYSTEM &&
        title in titles && button == "Close app"
}
