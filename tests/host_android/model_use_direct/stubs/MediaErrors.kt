package ai.tama.media
object AutomaticAvatarGuard { class Blocked: Exception() }
object ImageAttemptTracker { class Stopped: Exception() }
object ImageDispatch { class AuthorizationChanged: Exception() }
