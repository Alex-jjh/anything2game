package com.anything2game.server.api

import com.anything2game.server.config.Limits
import com.anything2game.server.store.InstallRecord
import com.anything2game.server.store.Store
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Per-install daily quotas (persisted) plus two global cost guards (in memory, reset on restart) that stop one person from
 * getting around the per-install quota by creating many installs.
 */
class Quotas(private val store: Store, private val limits: Limits, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val installTimes = ArrayDeque<Long>()
    private var globalDay = ""
    private var globalCompiles = 0

    private fun day(): String = LocalDate.ofEpochDay(clock() / 86_400_000L).toString()

    fun checkInstallCreation() = synchronized(lock) {
        val now = clock()
        while (installTimes.isNotEmpty() && now - installTimes.first() > 3_600_000L) installTimes.removeFirst()
        if (installTimes.size >= limits.installsPerHour) throw ApiException.quota("Too many new installs right now. Try again later.")
        installTimes.addLast(now)
    }

    fun consumeCompile(install: InstallRecord) {
        synchronized(lock) {
            val today = day()
            if (globalDay != today) { globalDay = today; globalCompiles = 0 }
            if (globalCompiles >= limits.globalCompilesPerDay) throw ApiException.quota("The service is at capacity today. Try again tomorrow.")
        }
        if (!store.tryConsumeQuota(install.installId, "compile", day(), limits.compilesPerInstallPerDay)) {
            throw ApiException.quota("Daily compile limit of ${limits.compilesPerInstallPerDay} reached for this install.")
        }
        synchronized(lock) { globalCompiles++ }
    }

    fun consumeAsk(install: InstallRecord) {
        if (!store.tryConsumeQuota(install.installId, "ask", day(), limits.asksPerInstallPerDay)) {
            throw ApiException.quota("Daily question limit of ${limits.asksPerInstallPerDay} reached for this install.")
        }
    }
}
