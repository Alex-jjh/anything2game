package com.anything2game.server.api

import com.anything2game.server.store.InstallRecord
import com.anything2game.server.store.Store
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Install tokens. This is NOT user authentication: the app has anonymous public users and the API is a public API.
 * A token only scopes quota and document ownership to one installation (another install's resources answer 404).
 * 256 random bits, base64url; only a SHA-256 hash is stored.
 */
class InstallAuth(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()

    data class Issued(val record: InstallRecord, val token: String)

    fun issue(): Issued {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val id = "ins_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))
        val record = InstallRecord(installId = id, tokenHash = hash(token), createdAt = clock())
        store.createInstall(record)
        return Issued(record, token)
    }

    fun authenticate(authorizationHeader: String?): InstallRecord? {
        val value = authorizationHeader?.trim() ?: return null
        if (!value.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        val token = value.substring(7).trim()
        if (token.isEmpty() || token.length > 100) return null
        return store.findInstallByTokenHash(hash(token))
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

val InstallKey = AttributeKey<InstallRecord>("a2g.install")

class InstallAuthConfig {
    lateinit var auth: InstallAuth
    /** The only routes that work without a token, as "METHOD /path". Everything else is denied by default. */
    var publicRoutes: Set<String> = emptySet()
}

/**
 * Default deny: everything inside a route that installs this plugin needs `Authorization: Bearer <install token>`,
 * except the exact (method, path) pairs in [InstallAuthConfig.publicRoutes]. A route added later is protected without anyone remembering to.
 */
val InstallAuthPlugin = createRouteScopedPlugin("InstallAuth", ::InstallAuthConfig) {
    val auth = pluginConfig.auth
    val public = pluginConfig.publicRoutes
    onCall { call ->
        if ("${call.request.httpMethod.value} ${call.request.path().trimEnd('/')}" in public) return@onCall
        val install = auth.authenticate(call.request.headers[HttpHeaders.Authorization])
            ?: throw ApiException.unauthorized()
        call.attributes.put(InstallKey, install)
    }
}

val ApplicationCall.install: InstallRecord get() = attributes[InstallKey]
