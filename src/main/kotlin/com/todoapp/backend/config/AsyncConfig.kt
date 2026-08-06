package com.todoapp.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * The pool that FCM fan-out runs on, so a push never occupies a request thread or holds a database
 * transaction open across a network round-trip (see PushDispatcher).
 *
 * Deliberately small and deliberately bounded. Pushes are I/O-bound and low-volume, and this runs on
 * a single small instance next to a serverless database — an unbounded queue would happily buffer a
 * fan-out storm into an OOM. `CallerRunsPolicy` is the backpressure: when the queue is full the
 * emitting thread sends the push itself, which slows the producer down instead of dropping the
 * notification.
 */
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean("pushExecutor")
    fun pushExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        setQueueCapacity(500)
        setThreadNamePrefix("push-")
        setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        // Let an in-flight push finish on shutdown rather than vanishing mid-send.
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS)
        initialize()
    }

    private companion object {
        const val AWAIT_TERMINATION_SECONDS = 10
    }
}
