package com.anything2game.server.api

import com.anything2game.server.store.InstallRecord
import com.anything2game.server.store.InstallRepository
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class PrincipalKind { INSTALL, USER }

/**
 * Who is calling. [ownerId] is the only identity the rest of the server knows: it scopes quotas and ownership of documents, jobs,
 * packages and frames. `install:<installId>` for an anonymous install token, `user:<sub>` for a signed-in user.
 */
data class Principal(val ownerId: String, val kind: PrincipalKind)

/** Turns the `Authorization` header into a [Principal], or null when the credentials are missing or not valid (the route answers 401). */
fun interface Authenticator {
    fun authenticate(authorizationHeader: String?): Principal?
}

/** The value after `Bearer ` (case-insensitive scheme), or null. */
internal fun bearerToken(header: String?, maxLength: Int): String? {
    val value = header?.trim() ?: return null
    if (!value.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
    val token = value.substring(7).trim()
    return token.takeIf { it.isNotEmpty() && it.length <= maxLength }
}

/**
 * Install tokens. This is NOT user authentication: the app has anonymous public users and the API is a public API.
 * A token only scopes quota and document ownership to one installation (another install's resources answer 404).
 * 256 random bits, base64url; only a SHA-256 hash is stored.
 */
class InstallTokenAuthenticator(private val installs: InstallRepository, private val clock: () -> Long = System::currentTimeMillis) : Authenticator {
    private val random = SecureRandom()

    data class Issued(val record: InstallRecord, val token: String)

    fun issue(): Issued {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val id = "ins_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))
        val record = InstallRecord(installId = id, tokenHash = hash(token), createdAt = clock())
        installs.createInstall(record)
        return Issued(record, token)
    }

    override fun authenticate(authorizationHeader: String?): Principal? {
        val token = bearerToken(authorizationHeader, maxLength = 100) ?: return null
        val install = installs.findInstallByTokenHash(hash(token)) ?: return null
        return Principal(ownerIdOfInstall(install.installId), PrincipalKind.INSTALL)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        fun ownerIdOfInstall(installId: String) = "install:$installId"
    }
}

/**
 * A token that looks like a JWT goes to [jwt] (null when sign-in is not configured: such a token is simply refused) and is never
 * tried as an install token; everything else is an install token.
 */
class CompositeAuthenticator(private val jwt: Authenticator?, private val install: Authenticator) : Authenticator {
    override fun authenticate(authorizationHeader: String?): Principal? {
        val token = bearerToken(authorizationHeader, maxLength = MAX_JWT_LENGTH) ?: return null
        return if (looksLikeJwt(token)) jwt?.authenticate(authorizationHeader) else install.authenticate(authorizationHeader)
    }

    companion object {
        const val MAX_JWT_LENGTH = 8192

        /** Three dot-separated parts and a JSON header (`eyJ` is base64url of `{"`). The signature part may be empty (`alg: none`): the verifier refuses it. */
        fun looksLikeJwt(token: String): Boolean = token.startsWith("eyJ") && token.count { it == '.' } == 2
    }
}

val PrincipalKey = AttributeKey<Principal>("a2g.principal")

class AuthConfig {
    lateinit var authenticator: Authenticator
    /** The only routes that work without a token, as "METHOD /path". Everything else is denied by default. */
    var publicRoutes: Set<String> = emptySet()
}

/**
 * Default deny: everything inside a route that installs this plugin needs `Authorization: Bearer <token>` that the [Authenticator]
 * accepts, except the exact (method, path) pairs in [AuthConfig.publicRoutes]. A route added later is protected without anyone remembering to.
 */
val AuthPlugin = createRouteScopedPlugin("A2gAuth", ::AuthConfig) {
    val authenticator = pluginConfig.authenticator
    val public = pluginConfig.publicRoutes
    onCall { call ->
        if ("${call.request.httpMethod.value} ${call.request.path().trimEnd('/')}" in public) return@onCall
        val principal = authenticator.authenticate(call.request.headers[HttpHeaders.Authorization]) ?: throw ApiException.unauthorized()
        call.attributes.put(PrincipalKey, principal)
    }
}

val ApplicationCall.principal: Principal get() = attributes[PrincipalKey]
