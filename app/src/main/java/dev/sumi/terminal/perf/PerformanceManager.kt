package dev.sumi.terminal.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalState(val label: String, val badge: String) {
    NOMINAL("Nominal", "ADPF • FRIO"),
    MODERATE("Moderado", "ADPF • MORNO"),
    SEVERE("Térmico Alto", "ADPF • THROTTLE")
}

class PerformanceManager(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val _thermalState = MutableStateFlow(ThermalState.NOMINAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                powerManager?.addThermalStatusListener { status ->
                    _thermalState.value = when (status) {
                        PowerManager.THERMAL_STATUS_NONE,
                        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.NOMINAL
                        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                        else -> ThermalState.SEVERE
                    }
                }
            }
        }
    }

    fun boostReaderThread(tid: Int) {
        if (tid <= 0) return
        runCatching {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DISPLAY)
        }
    }
}
