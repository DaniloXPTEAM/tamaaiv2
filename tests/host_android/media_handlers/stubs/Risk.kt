package io.github.nanomuse.guard
sealed class GateOutcome { data class Allowed(val notice:String?=null):GateOutcome(); data class Denied(val message:String):GateOutcome() }
object RiskGate { suspend fun checkShell(sessionId:String, command:String):GateOutcome = GateOutcome.Denied("host fixture") }
