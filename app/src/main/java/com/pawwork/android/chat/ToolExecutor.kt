package com.pawwork.android.chat

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * App-scoped background executor for tool calls.
 *
 * The old flow ran tools synchronously on the fragment's view-lifecycle coroutine and
 * relied on the runners' own short internal latches (30 s JS, 240 s Python…), so a long
 * image calculation or file-write job in run_code was killed mid-flight, and any view
 * teardown (rotation, tab switch) cancelled the whole agent loop.
 *
 * This executor lives for the process lifetime (object singleton + daemon threads), NOT
 * the fragment: a tool keeps running in the background even if the chat view is recreated,
 * and the agent loop can wait on it for as long as the tool asks — the LLM simply waits.
 *
 * Usage (from the agent loop):
 *   val job = ToolExecutor.submit(ctx, name, args, timeoutSeconds = 1500)
 *   val result = ToolExecutor.await(job, timeoutSeconds + 30)   // blocks; long-running allowed
 *   job.elapsedSeconds()  // for the "running for 1m 23s…" chat ticker
 *   job.cancel()          // interrupts a latch-waiting runner
 */
object ToolExecutor {

    /** Default ceiling for one tool call (incl. Pyodide boot). 25 minutes. */
    const val DEFAULT_TIMEOUT_SECONDS = 1500L

    private val exec = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "pawwork-tool-exec").apply { isDaemon = true }
    }
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val ids = AtomicLong(0)

    /** A submitted tool run. Immutable identity; result fields are set when it settles. */
    class Job(
        val id: Long,
        val name: String,
        val arguments: String,
        val startedAtMs: Long = System.currentTimeMillis(),
    ) {
        @Volatile var done: Boolean = false
            internal set
        @Volatile var result: String? = null
            internal set
        private var future: Future<*>? = null

        internal fun attach(f: Future<*>) { future = f }

        fun elapsedSeconds(): Long = (System.currentTimeMillis() - startedAtMs) / 1000

        /** Best-effort cancel: interrupts the runner thread; WebView latch waits are interruptible. */
        fun cancel() {
            future?.cancel(true)
        }
    }

    /**
     * Submit a tool call for background execution. The tool's own timeout budget is read
     * from its arguments (run_code: timeout_seconds) so the runner and the loop agree.
     */
    fun submit(context: Context, name: String, arguments: String): Job {
        // A negative/zero budget means "run to completion" — no artificial kill.
        val job = Job(ids.incrementAndGet(), name, arguments)
        val f = exec.submit {
            try {
                job.result = ToolRegistry.execute(context, name, arguments)
            } catch (e: java.util.concurrent.CancellationException) {
                job.result = JSONObject().put("ok", false)
                    .put("error", "tool cancelled (interrupted)").toString()
            } catch (e: Exception) {
                job.result = JSONObject().put("ok", false)
                    .put("error", e.message ?: e.javaClass.simpleName).toString()
            } finally {
                job.done = true
                jobs.remove(job.id)
            }
        }
        job.attach(f)
        jobs[job.id] = job
        return job
    }

    /** Block until the job settles or the budget expires. 0 / negative = wait indefinitely. */
    fun await(job: Job, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): String {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).coerceAtLeast(0)
        while (!job.done) {
            if (timeoutSeconds > 0 && System.currentTimeMillis() >= deadline) break
            Thread.sleep(200)
        }
        return if (job.done) {
            job.result ?: JSONObject().put("ok", false).put("error", "no result").toString()
        } else {
            JSONObject().put("ok", false)
                .put("error", "background tool still running after ${timeoutSeconds}s (job ${job.id})")
                .toString()
        }
    }

    /** Jobs still running right now (survived view teardown, user navigated, etc.). */
    fun active(): List<Job> = jobs.values.toList()

    fun activeCount(): Int = jobs.size

    /** nominal method kept thin; the pool never needs to be stopped for process lifetime */
    fun shutdownForTesting() {
        exec.shutdownNow()
        try { exec.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }
}