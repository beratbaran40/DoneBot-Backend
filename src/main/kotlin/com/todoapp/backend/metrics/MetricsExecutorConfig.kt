package com.todoapp.backend.metrics

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The thread every metrics write happens on — never the request thread.
 *
 * Metrics are strictly best-effort: a stalled or failing write must never slow down, block, or fail a
 * user's request. Three choices enforce that:
 *
 *  - **One thread.** Writes are tiny and rare (at most one insert per user per day); serialising them
 *    keeps connection use to a single pooled connection and makes ordering trivial.
 *  - **Bounded queue + DiscardPolicy.** If writes ever back up — a cold Neon compute can take seconds
 *    to resume — new tasks are dropped rather than queued without limit or, worse, run on the caller's
 *    thread. Losing a day marker is acceptable; adding seconds to a user's request is not.
 *  - **Traffic-driven only.** There is no timer flushing this queue. A periodic background query is
 *    exactly what keeps a serverless Postgres awake and billing, so writes happen only when a user was
 *    already talking to the API and the connection is warm anyway.
 */
@Configuration
class MetricsExecutorConfig {

    @Bean(name = [METRICS_WRITE_EXECUTOR], destroyMethod = "shutdown")
    fun metricsWriteExecutor(): ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "metrics-write").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    companion object {
        const val METRICS_WRITE_EXECUTOR = "metricsWriteExecutor"
        private const val QUEUE_CAPACITY = 1000
    }
}
