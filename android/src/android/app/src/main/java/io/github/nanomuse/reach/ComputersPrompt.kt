package io.github.nanomuse.reach

/** The device paragraph uses only descriptions supplied by the caller. */
object ComputersPrompt {
    fun build(labels: List<String>, onHub: Boolean, deepLink: String): String {
        return buildString {
            append("## Your devices (TAMA AI)\n")
            if (labels.isEmpty()) {
                append("No other device is connected right now. The way to add a computer: install nanoMuse Desktop there (https://nanomuse.cn/#download) and sign in with the same nanoMuse Cloud account as this phone — it appears under [Devices](${deepLink}) within seconds, on any network. ")
                append("When the user wants something done on their PC or Mac, say so in one line and point to the desktop app. ")
                if (!onHub) append("This phone is not on the hub itself; signing in to TAMA AI Cloud puts it there. ")
            } else {
                append("Connected: ").append(labels.joinToString("; ")).append(". ")
                append("`nanomuse-pc run \"<command>\" [--on <device>] [--cwd <dir>] [--timeout <s>]` runs a shell command there and returns exit code, stdout and stderr — the same approval rules as the phone's shell apply, decided on this phone before anything is sent; ")
                append("`nanomuse-pc ls [<path>]` lists a folder; `nanomuse-pc get <remote> [--name <file>]` copies a file into this chat's attachments (pictures then render with `![…](minis://attachments/<file>)`); `nanomuse-pc put <local> <remote>` copies one there; ")
                append("`nanomuse-pc open <url>` opens a page there; `nanomuse-pc screen` takes a picture of its screen into the attachments; `nanomuse-pc notify \"<text>\"` shows a notification there; `nanomuse-pc status` says which devices answer. ")
                append("`nanomuse-pc task \"<what to do, in plain words, with every detail>\" --on <device>` hands a whole task to the Muse running on that device and returns its answer — use it when the job needs that device's own apps, files, screen or judgement (a computer's browser or documents, another phone's apps); it may take minutes, and if that Muse needs an approval the card shows here. ")
                append("The other device's shell is the user's own account: same care as with `rm`, `git push --force` or anything that sends. When it does not answer, say it is offline and point to [Devices](${deepLink}).")
            }
        }
    }
}
