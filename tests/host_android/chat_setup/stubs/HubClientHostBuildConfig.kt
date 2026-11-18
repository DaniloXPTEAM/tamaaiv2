package com.openminis.app

/** Host-only substitutes for the immutable Android package ID and resource IDs. */
object BuildConfig { @Volatile var APPLICATION_ID = "ai.tama.assistant.tamav2test" }
object R {
    object string {
        const val nm_hub_err_disconnected = 1
        const val nm_hub_err_timeout = 2
        const val nm_hub_err_device_offline = 3
        const val nm_hub_err_busy = 4
        const val nm_hub_err_not_controllable = 5
        const val nm_hub_err_unknown_agent = 6
        const val nm_hub_err_no_session = 7
        const val nm_hub_err_unknown_action = 8
        const val nm_hub_err_bad_key = 9
        const val nm_hub_err_generic = 10
        const val nm_hub_err_id_reused = 11
        const val nm_hub_err_bad_call_id = 12
        const val nm_hub_err_id_limit = 13
    }
}
