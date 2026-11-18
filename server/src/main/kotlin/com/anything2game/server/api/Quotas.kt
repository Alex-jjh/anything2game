package com.anything2game.server.api

import com.anything2game.server.config.Limits
import com.anything2game.server.store.InstallRepository
import java.time.LocalDate

/**
 * Per-owner daily quotas plus two global cost guards that stop one person from getting around the per-owner quota by creating
 * many installs. All counters live in the store ([InstallRepository.tryConsume], an atomic conditional increment), so the limits
 * hold across restarts and across server instances that share a database. The install limit is a fixed window per clock hour.
 */
class Quotas(private val store: InstallRepository, private val limits: Limits, private val clock: () -> Long = System::currentTimeMillis) {
    private fun day(): String = LocalDate.ofEpochDay(clock() / 86_400_000L).toString()
    private fun hour(): String = (clock() / 3_600_000L).toString()

    fun checkInstallCreation() {
        if (!store.tryConsumeGlobal("installs", hour(), limits.installsPerHour)) throw ApiException.quota("Too many new installs right now. Try again later.")
    }

    fun consumeCompile(ownerId: String) {
        if (!store.tryConsumeQuota(ownerId, "compile", day(), limits.compilesPerInstallPerDay)) {
            throw ApiException.quota("Daily compile limit of ${limits.compilesPerInstallPerDay} reached for this install.")
        }
        if (!store.tryConsumeGlobal("compile", day(), limits.globalCompilesPerDay)) throw ApiException.quota("The service is at capacity today. Try again tomorrow.")
    }

    fun consumeAsk(ownerId: String) {
        if (!store.tryConsumeQuota(ownerId, "ask", day(), limits.asksPerInstallPerDay)) {
            throw ApiException.quota("Daily question limit of ${limits.asksPerInstallPerDay} reached for this install.")
        }
    }
}
