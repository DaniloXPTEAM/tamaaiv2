package io.github.nanomuse.media

/** The app's media-model paragraph with endpoint display strings supplied by the caller. */
object MediaPrompt {
    fun build(image: String?, video: String?, deepLink: String): String {
        return buildString {
            append("Media models. Unlike Muse, whose image and video models are built in, TAMA AI runs on three models the user configures: ")
            append("the chat model (you), an image model (avatar changes, pictures the user asks for) and a video model (the animated avatar, short clips). ")
            append("Image model: ").append(image ?: "NOT SET").append(". ")
            append("Video model: ").append(video ?: "NOT SET").append(".\n")
            append("- To make a picture the user asks for, run `nanomuse-media image --prompt \"...\" [--from <image path>]`; ")
            append("for a short clip, `nanomuse-media video --prompt \"...\" [--from <image path>] [--seconds 4-15]` (takes 1-5 minutes; say so first). ")
            append("Both print JSON with a `markdown` field — put that line in your reply so the file shows inline. `nanomuse-media status` prints what is configured.\n")
            append("- Avatar changes are handled by the app itself when the user writes \"change your avatar to ...\"; you only need to explain when it cannot work.\n")
            if (image == null) {
                append("- No image model is set: if the user asks to change your avatar or for a picture, say plainly that pictures need a provider with image models ")
                append("(Alibaba Cloud Bailian, OpenAI with an API key, Gemini or OpenRouter; a ChatGPT plan signed in through Codex, DeepSeek, Claude, Kimi and Groq have none), and give the link [Image & video models](")
                append(deepLink).append(") — it opens the setting. Never pretend to have drawn something.\n")
            }
            if (video == null) {
                append("- No video model is set: the avatar stays as still pictures, and clips cannot be made. If asked, explain that clips need a provider with video models ")
                append("(Alibaba Cloud Bailian: a Wan video model such as wan2.2-i2v-flash, picked in the setting) and give the link [Image & video models](").append(deepLink).append(").")
            }
        }.trimEnd()
    }
}
