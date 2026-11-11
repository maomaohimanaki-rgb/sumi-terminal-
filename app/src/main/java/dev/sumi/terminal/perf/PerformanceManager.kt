package dev.sumi.terminal.perf

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

enum class ThermalState(
    val label: String,
    val badge: String,
    val frameIntervalMs: Long,
    val fpsEstimate: Int
) {
    NONE("Nominal (60 FPS)", "ADPF • 16ms", 16L, 60),
    LIGHT("Leve (30 FPS)", "ADPF • 33ms", 33L, 30),
    MODERATE("Moderado (20 FPS)", "ADPF • 50ms", 50L, 20),
    SEVERE("Severo (10 FPS)", "ADPF • 100ms", 100L, 10),
    CRITICAL("Crítico (4 FPS)", "ADPF • 250ms", 250L, 4)
}

class PerformanceManager(private val context: Context) {

    companion object {
        const val FRAME_TARGET_NS: Long = 8_000_000L // 8ms target
    }

    private val hintLock = Any()
    private val powerManager = runCatching {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }.getOrNull()

    private val _thermalState = MutableStateFlow(ThermalState.NONE)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _frameIntervalMs = MutableStateFlow(16L)
    val frameIntervalMs: StateFlow<Long> = _frameIntervalMs.asStateFlow()

    private val trackedTids = CopyOnWriteArraySet<Int>()

    @Volatile
    private var hintSession: PerformanceHintManager.Session? = null

    init {
        val myTid = Process.myTid()
        if (myTid > 0) {
            trackedTids.add(myTid)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                powerManager?.addThermalStatusListener { status ->
                    val next = when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NONE
                        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
                        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                        else -> ThermalState.NONE
                    }
                    _thermalState.value = next
                    _frameIntervalMs.value = next.frameIntervalMs
                    updateTargetWorkDuration(next.frameIntervalMs * 1_000_000L / 2L)
                }
            }
        }
        rebuildHintSession()
    }

    fun attachReaderThread(tid: Int) {
        if (tid <= 0) return
        runCatching {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY)
        }
        if (trackedTids.add(tid)) {
            rebuildHintSession()
        }
    }

    fun detachReaderThread(tid: Int) {
        if (tid <= 0) return
        if (trackedTids.remove(tid)) {
            rebuildHintSession()
        }
    }

    private fun rebuildHintSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(hintLock) {
                runCatching {
                    val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
                    val tids = trackedTids.filter { it > 0 }.toIntArray()
                    if (tids.isEmpty()) return
                    runCatching { hintSession?.close() }
                    hintSession = runCatching {
                        phm.createHintSession(tids, FRAME_TARGET_NS)
                    }.getOrElse {
                        // Fallback to single main process TID if custom ROM/chipset rejects child TIDs
                        val selfTid = Process.myTid()
                        if (selfTid > 0) {
                            runCatching { phm.createHintSession(intArrayOf(selfTid), FRAME_TARGET_NS) }.getOrNull()
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    fun updateTargetWorkDuration(targetDurationNs: Long) {
        if (targetDurationNs <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(hintLock) {
                runCatching {
                    hintSession?.updateTargetWorkDuration(targetDurationNs)
                }
            }
        }
    }

    fun reportWorkDuration(actualDurationNs: Long) {
        if (actualDurationNs <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(hintLock) {
                runCatching {
                    hintSession?.reportActualWorkDuration(actualDurationNs)
                }
            }
        }
    }
}
