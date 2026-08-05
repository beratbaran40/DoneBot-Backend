package com.todoapp.backend.admin

/**
 * Identity echo for the panel's post-login gate.
 *
 * The panel signs in through the ordinary `POST /auth/login` (there is no separate admin login), so the
 * token it receives is indistinguishable from a normal user's. This endpoint is how it learns whether
 * that account may actually use the panel: 200 means yes, 403 means "signed in, but not an admin".
 *
 * [serverTime] and [zone] are here so every screen can label its numbers with the server's clock rather
 * than the browser's. All admin day-bucketing is UTC; showing an Istanbul-local timestamp next to a
 * UTC-bucketed chart is how you end up misreading a day boundary.
 */
data class AdminMeData(
    val id: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val serverTime: String,
    val zone: String = "UTC",
)
