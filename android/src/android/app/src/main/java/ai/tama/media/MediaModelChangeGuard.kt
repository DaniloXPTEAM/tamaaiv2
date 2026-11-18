package ai.tama.media

/**
 * A sibling edit or video model can have a different price. Until that actual
 * model can be reviewed and approved, the parallel package does not substitute
 * it silently. This does not establish that the selected model is free.
 */
internal object MediaModelChangeGuard {
    class Blocked : Exception("The media request would use a different model")

    fun requireSame(parallelTest: Boolean, selected: String, actual: String) {
        if (parallelTest && selected != actual) throw Blocked()
    }
}
