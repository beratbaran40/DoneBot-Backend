package com.todoapp.backend.metrics

/**
 * The panel's landing payload: every headline number in one request.
 *
 * [generatedAt] and [cacheAgeSeconds] are part of the contract rather than decoration. A dashboard that
 * shows a number without saying how old it is invites exactly the mistake that motivated this whole
 * project — reading a stale figure as current. The panel prints "updated HH:mm" from these.
 *
 * [zone] is always UTC. Every day boundary on this surface — activity, chat usage, task completion —
 * is bucketed in UTC, and Istanbul is UTC+3, so labelling the axis with the viewer's local zone would
 * silently shift every bar by three hours.
 */
data class AdminOverview(
    val generatedAt: String,
    val zone: String,
    val cacheAgeSeconds: Int,
    val users: UsersBlock,
    val engagement: EngagementBlock,
    val tasks: TasksBlock,
    val groups: GroupsBlock,
    val chat: ChatBlock,
    val moderation: ModerationBlock,
)

data class UsersBlock(
    val total: Long,
    val newToday: Long,
    val new7d: Long,
    val new30d: Long,
    val suspended: Long,
    val verified: Long,
    /** Keyed by the raw providers_csv value, e.g. "google" or "email,google". */
    val byProvider: Map<String, Long>,
)

/**
 * [dauOverMau] is the stickiness ratio — of the people who used DoneBot this month, how many use it on
 * any given day. It is the one engagement number that stays meaningful at every scale, because unlike
 * raw DAU it does not simply grow with installs.
 *
 * Retention here is **rolling**: [d1] is the share of users who were still active one day *or later*
 * after signing up. Classic "active on exactly day N" retention produces near-zero, noise-dominated
 * numbers at this scale. [AdminRetention] exposes the full cohort table for the real shape.
 *
 * Each figure is null when no cohort has aged enough to answer it honestly, rather than 0 — "we do not
 * know yet" and "nobody came back" must not render as the same bar.
 */
data class EngagementBlock(
    val dau: Long,
    val wau: Long,
    val mau: Long,
    val dauOverMau: Double?,
    val d1: Double?,
    val d7: Double?,
    val d30: Double?,
    val neverActive: Long,
)

/**
 * [completedToday] and [completionRate7d] are null until `tasks.completed_at` (V27) has data. The column
 * ships empty on purpose — historical completions were never timestamped and backfilling them from
 * created_at would be fiction — so a 0 here would read as "nobody completed anything today" when the
 * truth is "this was not measurable before today".
 */
data class TasksBlock(
    val total: Long,
    val personal: Long,
    val group: Long,
    val createdToday: Long,
    val created7d: Long,
    val completedToday: Long?,
    val completionRate7d: Double?,
    val routineCompletions7d: Long,
    val recurring: Long,
    val withPhotos: Long,
    val byCategory: Map<String, Long>,
)

data class GroupsBlock(
    val total: Long,
    val active7d: Long,
    val avgMembers: Double,
    val pendingInvites: Long,
)

/**
 * Tokens, not requests, are what Vertex bills for — a refused turn and a five-tool-call turn cost
 * wildly different amounts while counting as one request each.
 *
 * [errorRate7d] was unmeasurable before ChatUsageRecorder: ChatService only recorded on its success and
 * safety-refusal paths, so every quota rejection, outage and turn-deadline abort left no trace.
 */
data class ChatBlock(
    val requestsToday: Long,
    val requests7d: Long,
    val uniqueUsers7d: Long,
    val errorRate7d: Double?,
    val refusalRate7d: Double?,
    val promptTokens7d: Long,
    val responseTokens7d: Long,
    val avgServerMs7d: Long?,
)

/** [oldestOpenAgeHours] is the number that matters for policy: not how many reports, but how stale. */
data class ModerationBlock(
    val openChatReports: Long,
    val openContentReports: Long,
    val oldestOpenAgeHours: Long?,
)

/** One point of one series. [date] is an ISO date in UTC. */
data class SeriesPoint(val date: String, val value: Long)

/**
 * Every series for the requested window in one payload, gap-filled so a day with no events is a zero
 * rather than a missing point — a line chart that silently skips empty days misrepresents a flat period
 * as a continuous one.
 */
data class AdminTimeSeries(
    val from: String,
    val to: String,
    val zone: String,
    val series: Map<String, List<SeriesPoint>>,
)

data class AdminBreakdown(
    val dimension: String,
    val zone: String,
    val values: Map<String, Long>,
)

/** Share of a signup cohort still active on each day offset. [retained] is a fraction of [cohortSize]. */
data class RetentionCell(val dayOffset: Int, val retained: Int, val rate: Double)

data class RetentionCohort(
    val cohortStart: String,
    val cohortSize: Int,
    val cells: List<RetentionCell>,
)

data class AdminRetention(
    val zone: String,
    val dayOffsets: List<Int>,
    val cohorts: List<RetentionCohort>,
)

/**
 * The activation funnel. Signup is easy to measure and easy to over-read; what matters is how many of
 * those accounts ever created a task, and how many ever finished one.
 */
data class AdminFunnel(
    val zone: String,
    val windowDays: Int,
    val registered: Long,
    val createdFirstTask: Long,
    val completedFirstTask: Long,
)
