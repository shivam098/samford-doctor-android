package com.samford.doctor

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.Timer
import kotlin.concurrent.timer

/**
 * If a test is still running after [afterMs], dumps every Java thread's stack and the process
 * memory to logcat (tag StallWatchdog, captured in the per-test logcat file) and to
 * files/stall/<test>.txt, then again every [repeatMs]. Runs on its own thread, so it reports even
 * when the main thread never goes idle and the test's own timeouts can't fire.
 */
class StallDumpRule(private val afterMs: Long = 8 * 60_000L, private val repeatMs: Long = 3 * 60_000L) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val started = System.currentTimeMillis()
            val watchdog: Timer = timer("stall-watchdog", daemon = true, initialDelay = afterMs, period = repeatMs) {
                dump(description, System.currentTimeMillis() - started)
            }
            try {
                base.evaluate()
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun dump(description: Description, elapsedMs: Long) {
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val report = buildString {
            appendLine("${description.displayName} still running after ${elapsedMs / 1000} s")
            appendLine("memory: totalPss=${memory.totalPss} KB javaHeap=${memory.getMemoryStat("summary.java-heap")} KB nativeHeap=${memory.getMemoryStat("summary.native-heap")} KB graphics=${memory.getMemoryStat("summary.graphics")} KB")
            appendLine("runtime: used=${(runtime.totalMemory() - runtime.freeMemory()) / 1024} KB max=${runtime.maxMemory() / 1024} KB")
            for ((thread, stack) in Thread.getAllStackTraces().entries.sortedBy { if (it.key.name == "main") "" else it.key.name }) {
                appendLine()
                appendLine("\"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
                stack.forEach { appendLine("    at $it") }
            }
        }
        report.lineSequence().forEach { Log.w("StallWatchdog", it) }
        runCatching {
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "stall").apply { mkdirs() }
            File(dir, "${description.testClass?.simpleName}-${description.methodName}.txt").appendText(report + "\n")
        }
    }
}
