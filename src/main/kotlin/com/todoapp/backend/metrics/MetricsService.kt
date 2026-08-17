package com.todoapp.backend.metrics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Assembles the admin surface's numbers.
 *
 * Everything is computed live and then held for [MetricsProperties.overviewCacheSeconds]. The cache is
 * not about query speed — at this volume the whole overview is a handful of indexed counts — it is
 * about not waking a serverless Postgres compute every time a browser tab regains focus.
 *
 * All day boundaries are UTC. See [AdminOverview.zone].
 */
@Service
@Transactional(readOnly = true)
class MetricsService(
    private val repository: MetricsQueryRepository,
    private val properties: MetricsProperties,
) {

    @Volatile
    private var cached: Cached? = null

    private data class Cached(val at: Instant, val value: AdminOverview)

    fun overview(): AdminOverview {
        val now = Instant.now()
        val snapshot = cached
        if (snapshot != null) {
            val age = Duration.between(snapshot.at, now).seconds
            if (age < properties.overviewCacheSeconds) {
                return snapshot.value.copy(cacheAgeSeconds = age.toInt())
            }
        }
        val fresh = buildOverview(now)
        cached = Cached(now, fresh)
        return fresh
    }

    private fun buildOverview(now: Instant): AdminOverview {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val todayStart = today.startOfDayUtc()
        val sevenDaysAgo = today.minusDays(6)
        val thirtyDaysAgo = today.minusDays(29)

        val retention = rollingRetention(today)

        // completed_at ships empty (V27 deliberately backfills nothing), so distinguish "no data yet"
        // from "genuinely zero today" — rendering them the same would be a lie by omission.
        val hasCompletionData = repository.tasksWithCompletionTimestamp() > 0
        val created7d = repository.tasksCreatedSince(sevenDaysAgo.startOfDayUtc())
        val completed7d = if (hasCompletionData) {
            repository.tasksCompletedSince(sevenDaysAgo.startOfDayUtc())
        } else {
            null
        }

        val chat7d = repository.chatTotalsBetween(sevenDaysAgo, today)

        // Same "no data yet" vs "genuinely zero" split as completed_at above. pomodoro_sessions ships
        // empty and there is nothing to backfill it from, so every derived figure stays null until the
        // first row arrives from a released client.
        val hasPomodoroData = repository.pomodoroSessionsExist()
        val tomorrowStart = today.plusDays(1).startOfDayUtc()
        val pomo7d = repository.pomodoroTotalsBetween(sevenDaysAgo.startOfDayUtc(), tomorrowStart)
        val pomoToday = repository.pomodoroTotalsBetween(todayStart, tomorrowStart)

        val oldestOpen = repository.oldestOpenReport()

        return AdminOverview(
            generatedAt = now.toString(),
            zone = ZONE,
            cacheAgeSeconds = 0,
            users = UsersBlock(
                total = repository.totalUsers(),
                newToday = repository.usersCreatedSince(todayStart),
                new7d = repository.usersCreatedSince(sevenDaysAgo.startOfDayUtc()),
                new30d = repository.usersCreatedSince(thirtyDaysAgo.startOfDayUtc()),
                suspended = repository.usersWithStatus("SUSPENDED"),
                verified = repository.verifiedUsers(),
                byProvider = repository.usersByProvider(),
            ),
            engagement = EngagementBlock(
                dau = repository.activeUsersBetween(today, today),
                wau = repository.activeUsersBetween(sevenDaysAgo, today),
                mau = repository.activeUsersBetween(thirtyDaysAgo, today),
                dauOverMau = null,
                d1 = retention[1],
                d7 = retention[7],
                d30 = retention[30],
                neverActive = repository.neverActiveUsers(),
            ).withStickiness(),
            tasks = TasksBlock(
                total = repository.totalTasks(),
                personal = repository.tasksByScope(group = false),
                group = repository.tasksByScope(group = true),
                createdToday = repository.tasksCreatedSince(todayStart),
                created7d = created7d,
                completedToday = if (hasCompletionData) repository.tasksCompletedSince(todayStart) else null,
                completionRate7d = completed7d?.let { if (created7d == 0L) null else it.toDouble() / created7d },
                routineCompletions7d = repository.routineCompletionsSince(sevenDaysAgo.toEpochDay()),
                recurring = repository.recurringTasks(),
                withPhotos = repository.tasksWithPhotos(),
                byCategory = repository.tasksByCategory(),
            ),
            groups = GroupsBlock(
                total = repository.totalGroups(),
                active7d = repository.groupsActiveSince(sevenDaysAgo.startOfDayUtc()),
                avgMembers = repository.averageGroupMembers(),
                pendingInvites = repository.pendingInvitations(),
            ),
            chat = ChatBlock(
                requestsToday = repository.chatRequestsBetween(today, today),
                requests7d = chat7d.requests,
                uniqueUsers7d = chat7d.uniqueUsers,
                errorRate7d = chat7d.rate { it.errors },
                refusalRate7d = chat7d.rate { it.refusals },
                promptTokens7d = chat7d.promptTokens,
                responseTokens7d = chat7d.responseTokens,
                avgServerMs7d = if (chat7d.requests == 0L) null else chat7d.serverMs / chat7d.requests,
            ),
            pomodoro = PomodoroBlock(
                focusMinutesToday = if (hasPomodoroData) pomoToday.focusSeconds / SECONDS_PER_MINUTE else null,
                focusMinutes7d = if (hasPomodoroData) pomo7d.focusSeconds / SECONDS_PER_MINUTE else null,
                sessionsCompleted7d = if (hasPomodoroData) pomo7d.focusCompleted else null,
                completionRate7d = if (pomo7d.focusStarted == 0L) {
                    null
                } else {
                    pomo7d.focusCompleted.toDouble() / pomo7d.focusStarted
                },
                uniqueUsers7d = pomo7d.uniqueUsers,
                runs7d = pomo7d.runs,
                avgFocusMinutesPerUser7d = if (pomo7d.uniqueUsers == 0L) {
                    null
                } else {
                    pomo7d.focusSeconds / SECONDS_PER_MINUTE / pomo7d.uniqueUsers
                },
            ),
            moderation = ModerationBlock(
                openChatReports = repository.openReports("chat_reports"),
                openContentReports = repository.openReports("content_reports"),
                oldestOpenAgeHours = oldestOpen?.let { ChronoUnit.HOURS.between(it.toInstant(), now) },
            ),
        )
    }

    /**
     * Rolling retention for the three headline offsets, from a single query.
     *
     * "Rolling" means active on or after signup + N days, rather than exactly on day N. Classic day-N
     * retention is near-zero and noise-dominated at this scale — with a few dozen users, one person
     * opening the app decides the number.
     */
    private fun rollingRetention(today: LocalDate): Map<Int, Double?> {
        // The widest eligibility bound is D1's, so one fetch covers all three offsets.
        val rows = repository.signupAndLastActive(today.startOfDayUtc())
        return RETENTION_OFFSETS.associateWith { offset ->
            val eligible = rows.filter { !it.signupDay.isAfter(today.minusDays(offset.toLong())) }
            if (eligible.isEmpty()) {
                null
            } else {
                val threshold = offset.toLong()
                val retained = eligible.count { row ->
                    val last = row.lastActive
                    last != null && !last.isBefore(row.signupDay.plusDays(threshold))
                }
                retained.toDouble() / eligible.size
            }
        }
    }

    fun timeSeries(from: LocalDate, to: LocalDate): AdminTimeSeries {
        val series = mapOf(
            "newUsers" to repository.dailyNewUsers(from, to),
            "activeUsers" to repository.dailyActiveUsers(from, to),
            "tasksCreated" to repository.dailyTasksCreated(from, to),
            "tasksCompleted" to repository.dailyTasksCompleted(from, to),
            "chatRequests" to repository.dailyChatRequests(from, to),
            "pomodoroFocusMinutes" to repository.dailyPomodoroFocusMinutes(from, to),
        ).mapValues { (_, byDay) -> fillGaps(byDay, from, to) }

        return AdminTimeSeries(
            from = from.toString(),
            to = to.toString(),
            zone = ZONE,
            series = series,
        )
    }

    /**
     * A day with no events must appear as a zero, not be absent. A line chart that silently skips empty
     * days draws a continuous slope across a gap and turns a dead week into apparent steady activity.
     */
    private fun fillGaps(byDay: Map<LocalDate, Long>, from: LocalDate, to: LocalDate): List<SeriesPoint> =
        generateSequence(from) { day -> day.plusDays(1).takeIf { !it.isAfter(to) } }
            .map { day -> SeriesPoint(day.toString(), byDay[day] ?: 0L) }
            .toList()

    fun breakdown(dimension: String): AdminBreakdown {
        val values = when (dimension) {
            "category" -> repository.tasksByCategory()
            "provider" -> repository.usersByProvider()
            "recurrence" -> repository.tasksByRecurrence()
            "groupSize" -> repository.groupsBySize()
            else -> throw IllegalArgumentException("Unknown dimension: $dimension")
        }
        return AdminBreakdown(dimension = dimension, zone = ZONE, values = values)
    }

    fun retention(cohortWeeks: Int): AdminRetention {
        val today = LocalDate.now(ZoneOffset.UTC)
        val since = today.minusWeeks(cohortWeeks.toLong()).startOfDayUtc()

        val sizesByDay = repository.cohortSizes(since)
        val pairs = repository.cohortPairs(since)

        // Group signup days into ISO weeks so a handful of daily signups still make a readable row.
        val cohortSizes = sizesByDay.entries.groupingBy { it.key.weekStart() }
            .fold(0) { acc, entry -> acc + entry.value }

        val retainedByCohort = mutableMapOf<LocalDate, MutableMap<Int, MutableSet<Long>>>()
        pairs.forEach { (userId, signupDay, activeDay) ->
            val offsetDays = ChronoUnit.DAYS.between(signupDay, activeDay).toInt()
            if (offsetDays < 0) return@forEach
            val week = signupDay.weekStart()
            COHORT_OFFSETS.filter { offsetDays >= it }.forEach { offset ->
                retainedByCohort.getOrPut(week) { mutableMapOf() }
                    .getOrPut(offset) { mutableSetOf() }
                    .add(userId)
            }
        }

        val cohorts = cohortSizes.entries.sortedBy { it.key }.map { (week, size) ->
            RetentionCohort(
                cohortStart = week.toString(),
                cohortSize = size,
                cells = COHORT_OFFSETS.map { offset ->
                    val retained = retainedByCohort[week]?.get(offset)?.size ?: 0
                    RetentionCell(
                        dayOffset = offset,
                        retained = retained,
                        rate = if (size == 0) 0.0 else retained.toDouble() / size,
                    )
                },
            )
        }

        return AdminRetention(zone = ZONE, dayOffsets = COHORT_OFFSETS, cohorts = cohorts)
    }

    fun funnel(windowDays: Int): AdminFunnel {
        val since = LocalDate.now(ZoneOffset.UTC).minusDays(windowDays.toLong()).startOfDayUtc()
        return AdminFunnel(
            zone = ZONE,
            windowDays = windowDays,
            registered = repository.registeredSince(since),
            createdFirstTask = repository.usersWhoCreatedATask(since),
            completedFirstTask = repository.usersWhoCompletedATask(since),
        )
    }

    private fun LocalDate.startOfDayUtc(): OffsetDateTime = atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()

    /** Monday of this date's ISO week. */
    private fun LocalDate.weekStart(): LocalDate = minusDays((dayOfWeek.value - 1).toLong())

    private fun ChatTotals.rate(selector: (ChatTotals) -> Long): Double? =
        if (requests == 0L) null else selector(this).toDouble() / requests

    private fun EngagementBlock.withStickiness(): EngagementBlock =
        copy(dauOverMau = if (mau == 0L) null else dau.toDouble() / mau)

    private companion object {
        const val ZONE = "UTC"
        const val SECONDS_PER_MINUTE = 60L
        val RETENTION_OFFSETS = listOf(1, 7, 30)
        val COHORT_OFFSETS = listOf(1, 7, 14, 30)
    }
}
