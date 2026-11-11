package dev.sumi.terminal

import android.app.Application
import dev.sumi.terminal.data.preferences.TerminalPreferences
import dev.sumi.terminal.data.repository.WorkspaceRepository
import dev.sumi.terminal.install.RootfsInstaller
import dev.sumi.terminal.perf.PerformanceManager
import dev.sumi.terminal.service.AgentAlertDispatcher
import dev.sumi.terminal.service.TerminalSessionManager

class SumiApp : Application() {

    lateinit var preferences: TerminalPreferences
        private set
    lateinit var workspaceRepository: WorkspaceRepository
        private set
    lateinit var rootfsInstaller: RootfsInstaller
        private set
    lateinit var performanceManager: PerformanceManager
        private set
    lateinit var sessionManager: TerminalSessionManager
        private set
    lateinit var alertDispatcher: AgentAlertDispatcher
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = TerminalPreferences(this)
        workspaceRepository = WorkspaceRepository(this)
        rootfsInstaller = RootfsInstaller(this, workspaceRepository)
        performanceManager = PerformanceManager(this)
        sessionManager = TerminalSessionManager(workspaceRepository, performanceManager)
        alertDispatcher = AgentAlertDispatcher()
    }
}
