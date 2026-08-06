package com.todoapp.backend.user

import com.todoapp.backend.notif.inbox.NotificationType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import java.util.Optional

/**
 * Who a push actually reaches. Both gates matter — the master switch and the per-type mute — and the
 * failure mode is silent either way: a muted type that still buzzes, or a whole account that stops
 * hearing anything because a default read the wrong way.
 */
class UserPreferencesServiceTest {

    private val repo: UserPreferencesRepository = Mockito.mock(UserPreferencesRepository::class.java)
    private val service = UserPreferencesService(repo)

    @Test
    fun `a user with no row at all still receives push`() {
        // Every account predating the preferences table is in this state, so "absent" must read as
        // the permissive default rather than as "opted out".
        given(repo.findAllById(listOf(1L))).willReturn(emptyList())

        assertThat(service.pushEnabledUserIds(listOf(1L), NotificationType.TASK_ASSIGNED)).containsExactly(1L)
    }

    @Test
    fun `the master switch silences every type`() {
        given(repo.findAllById(listOf(1L))).willReturn(
            listOf(UserPreferencesEntity(userId = 1L, pushEnabled = false)),
        )

        NotificationType.entries.forEach { type ->
            assertThat(service.pushEnabledUserIds(listOf(1L), type)).isEmpty()
        }
    }

    @Test
    fun `a muted type is dropped while the others still go out`() {
        given(repo.findAllById(listOf(1L))).willReturn(
            listOf(UserPreferencesEntity(userId = 1L, pushDisabledTypes = "TASK_COMPLETED")),
        )

        assertThat(service.pushEnabledUserIds(listOf(1L), NotificationType.TASK_COMPLETED)).isEmpty()
        assertThat(service.pushEnabledUserIds(listOf(1L), NotificationType.TASK_ASSIGNED)).containsExactly(1L)
    }

    @Test
    fun `each recipient is judged on their own preferences`() {
        given(repo.findAllById(listOf(1L, 2L, 3L))).willReturn(
            listOf(
                UserPreferencesEntity(userId = 1L, pushDisabledTypes = "TASK_ASSIGNED"),
                UserPreferencesEntity(userId = 2L, pushEnabled = false),
                // 3 has no row: default, so still included.
            ),
        )

        assertThat(service.pushEnabledUserIds(listOf(1L, 2L, 3L), NotificationType.TASK_ASSIGNED))
            .containsExactly(3L)
    }

    @Test
    fun `a stored name that is no longer a type mutes nothing`() {
        // A type removed from the enum leaves its name behind in existing CSVs; treating that as a
        // live mute would silence whichever type happened to be compared against it.
        given(repo.findAllById(listOf(1L))).willReturn(
            listOf(UserPreferencesEntity(userId = 1L, pushDisabledTypes = "SOMETHING_RETIRED")),
        )

        assertThat(service.pushEnabledUserIds(listOf(1L), NotificationType.TASK_ASSIGNED)).containsExactly(1L)
    }

    @Test
    fun `omitting the type set leaves the stored mutes alone`() {
        // An older client only knows the master switch. Treating its absent field as "clear them all"
        // would silently un-mute everything the moment such a client toggled push.
        val row = UserPreferencesEntity(userId = 1L, pushDisabledTypes = "TASK_COMPLETED")
        given(repo.findById(1L)).willReturn(Optional.of(row))
        given(repo.save(row)).willReturn(row)

        val updated = service.update(1L, UpdateUserPreferencesRequest(pushEnabled = true, disabledTypes = null))

        assertThat(updated.disabledTypes).containsExactly("TASK_COMPLETED")
    }

    @Test
    fun `an explicit set replaces the stored mutes`() {
        val row = UserPreferencesEntity(userId = 1L, pushDisabledTypes = "TASK_COMPLETED")
        given(repo.findById(1L)).willReturn(Optional.of(row))
        given(repo.save(row)).willReturn(row)

        val updated = service.update(
            1L,
            UpdateUserPreferencesRequest(pushEnabled = true, disabledTypes = setOf("TASK_DUE_SOON")),
        )

        assertThat(updated.disabledTypes).containsExactly("TASK_DUE_SOON")
    }
}
