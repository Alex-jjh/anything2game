package com.anything2game.server

import com.anything2game.contract.CompileJobState
import com.anything2game.contract.CompileJobStatus
import com.anything2game.server.api.CompositeAuthenticator
import com.anything2game.server.api.InstallTokenAuthenticator
import com.anything2game.server.api.OidcJwtAuthenticator
import com.anything2game.server.api.Principal
import com.anything2game.server.api.PrincipalKind
import com.anything2game.server.config.ConfigException
import com.anything2game.server.config.OidcConfig
import com.anything2game.server.config.ServerConfig
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

class OidcAuthTest {
    private val issuer = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_TEST"
    private val clientId = "app-client-123"
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private val otherKey: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate() // same kid, different key
    private val auth = OidcJwtAuthenticator(issuer, clientId, ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())))

    private fun claims(
        iss: String = issuer, sub: String? = "user-42", exp: Long = 3600, nbf: Long? = null, extra: Map<String, Any> = mapOf("client_id" to clientId, "token_use" to "access"),
    ): JWTClaimsSet = JWTClaimsSet.Builder().issuer(iss).apply {
        if (sub != null) subject(sub)
        expirationTime(Date(System.currentTimeMillis() + exp * 1000))
        if (nbf != null) notBeforeTime(Date(System.currentTimeMillis() + nbf * 1000))
        extra.forEach { (k, v) -> claim(k, v) }
    }.build()

    private fun sign(c: JWTClaimsSet, signer: RSAKey = key, alg: JWSAlgorithm = JWSAlgorithm.RS256, kid: String = "k1"): String =
        SignedJWT(JWSHeader.Builder(alg).keyID(kid).type(JOSEObjectType.JWT).build(), c).also { it.sign(RSASSASigner(signer)) }.serialize()

    private fun check(token: String?) = auth.authenticate(token?.let { "Bearer $it" })

    @Test fun `a valid Cognito-style access token gives user principal`() {
        assertEquals(Principal("user:user-42", PrincipalKind.USER), check(sign(claims())))
    }

    @Test fun `a valid token with aud instead of client_id is accepted when there is no client_id`() {
        val c = claims(extra = mapOf("aud" to listOf("other", clientId)))
        assertEquals("user:user-42", check(sign(c))!!.ownerId)
        assertNull("wrong audience", check(sign(claims(extra = mapOf("aud" to "someone-else")))))
        assertNull("neither client_id nor aud", check(sign(claims(extra = emptyMap()))))
    }

    @Test fun `expired, not yet valid, wrong issuer, wrong client, ID token and missing subject are refused`() {
        assertNull("expired", check(sign(claims(exp = -3600))))
        assertNull("not before in the future", check(sign(claims(nbf = 3600))))
        assertEquals("a little clock skew is fine", "user:user-42", check(sign(claims(exp = -10)))!!.ownerId)
        assertNull("wrong issuer", check(sign(claims(iss = "https://evil.example.com/pool"))))
        assertNull("wrong client", check(sign(claims(extra = mapOf("client_id" to "someone-else", "token_use" to "access")))))
        assertNull("client_id wins over a matching aud", check(sign(claims(extra = mapOf("client_id" to "x", "aud" to clientId)))))
        assertNull("an ID token is not an access token", check(sign(claims(extra = mapOf("aud" to clientId, "token_use" to "id")))))
        assertNull("no subject", check(sign(claims(sub = null))))
    }

    @Test fun `bad signatures are refused`() {
        assertNull("signed by another key under the same kid", check(sign(claims(), signer = otherKey)))
        val good = sign(claims())
        val parts = good.split('.')
        val forged = JWTClaimsSet.Builder(claims()).subject("admin").build()
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forged.toString().toByteArray())
        assertNull("payload swapped, signature kept", check("${parts[0]}.$payload.${parts[2]}"))
        assertNull("signature cut off", check("${parts[0]}.${parts[1]}."))
        assertNull("garbage signature", check("${parts[0]}.${parts[1]}.AAAA"))
        assertNull("unknown key id", check(sign(claims(), kid = "unknown")))
    }

    @Test fun `alg none is refused`() {
        val plain = PlainJWT(claims()).serialize() // header {"alg":"none"}, empty signature
        assertTrue(CompositeAuthenticator.looksLikeJwt(plain))
        assertNull(check(plain))
        // a hand-made one that even claims the right key id
        val header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none","kid":"k1","typ":"JWT"}""".toByteArray())
        assertNull(check("$header.${plain.split('.')[1]}."))
    }

    @Test fun `only RS256 is accepted, HMAC with the public key as secret and other RSA algorithms are not`() {
        val hmacSecret = key.toRSAPublicKey().encoded.copyOf(64)
        val hs = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims()).also { it.sign(MACSigner(hmacSecret)) }.serialize()
        assertNull(check(hs))
        assertNull(check(sign(claims(), alg = JWSAlgorithm.RS384)))
    }

    @Test fun `missing or malformed headers give nothing`() {
        assertNull(auth.authenticate(null))
        assertNull(auth.authenticate("Basic abc"))
        assertNull(auth.authenticate("Bearer "))
        assertNull(auth.authenticate("Bearer not.a.jwt"))
        assertNull(auth.authenticate("Bearer " + "a".repeat(9_000)))
    }

    @Test fun `the composite sends JWTs to the verifier and everything else to the install token`() {
        val installs = TestEnv()
        installs.use { env ->
            val tokens = InstallTokenAuthenticator(env.store)
            val issued = tokens.issue()
            val composite = CompositeAuthenticator(auth, tokens)
            assertEquals(Principal("install:${issued.record.installId}", PrincipalKind.INSTALL), composite.authenticate("Bearer ${issued.token}"))
            assertEquals("user:user-42", composite.authenticate("Bearer ${sign(claims())}")!!.ownerId)
            assertNull(composite.authenticate("Bearer ${sign(claims(exp = -3600))}"))
            assertNull(composite.authenticate("Bearer nonsense"))
            // sign-in not configured: a JWT is refused and never mistaken for an install token
            assertNull(CompositeAuthenticator(null, tokens).authenticate("Bearer ${sign(claims())}"))
            assertNotNull(CompositeAuthenticator(null, tokens).authenticate("Bearer ${issued.token}"))
        }
    }

    @Test fun `keys are fetched over HTTPS-style JWKS from a URL and an unknown key id is refused`() {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing { get("/jwks.json") { call.respondText(JWKSet(key.toPublicJWK()).toString(), ContentType.Application.Json) } }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val remote = OidcJwtAuthenticator.fromConfig(OidcConfig(issuer, clientId, "http://127.0.0.1:$port/jwks.json"))
            assertEquals("user:user-42", remote.authenticate("Bearer ${sign(claims())}")!!.ownerId)
            assertNull(remote.authenticate("Bearer ${sign(claims(), signer = otherKey)}"))
        } finally {
            server.stop(100, 500)
        }
    }

    // ---- through the HTTP API -----------------------------------------------------------------------------------

    @Test fun `a signed-in user owns documents and jobs and nobody else sees them`() = TestEnv(jwt = auth).use { env ->
        env.run {
            val api = api()
            val alice = sign(claims(sub = "alice"))
            val bob = sign(claims(sub = "bob"))
            val (_, installToken) = api.install()

            val doc = api.document(alice, pagesOf(Examples.retrieval))
            assertEquals("user:alice", env.store.getDocument(doc.documentId)!!.ownerId)
            val job = api.decode(CompileJobStatus.serializer(), api.compile(alice, doc.documentId))
            assertEquals(CompileJobState.READY, api.awaitJob(alice, job.jobId).state)

            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, "/v1/compile-jobs/${job.jobId}", bob).status)
            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Get, "/v1/compile-jobs/${job.jobId}", installToken).status)
            assertEquals(HttpStatusCode.NotFound, api.send(HttpMethod.Delete, "/v1/documents/${doc.documentId}", bob).status)

            // invalid tokens are plain 401s
            assertEquals(HttpStatusCode.Unauthorized, api.send(HttpMethod.Get, "/v1/compile-jobs/${job.jobId}", sign(claims(exp = -3600))).status)
            assertEquals(HttpStatusCode.Unauthorized, api.send(HttpMethod.Get, "/v1/compile-jobs/${job.jobId}", PlainJWT(claims()).serialize()).status)
            // the user's quota is theirs alone
            assertEquals(HttpStatusCode.NoContent, api.send(HttpMethod.Delete, "/v1/documents/${doc.documentId}", alice).status)
        }
    }

    // ---- configuration --------------------------------------------------------------------------------------------

    @Test fun `OIDC is off unless an issuer is set, then the client id is required`() {
        assertNull(ServerConfig.fromEnv(emptyMap()).oidc)
        try { ServerConfig.fromEnv(mapOf("A2G_OIDC_ISSUER" to issuer)); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("A2G_OIDC_CLIENT_ID")) }
        val c = ServerConfig.fromEnv(mapOf("A2G_OIDC_ISSUER" to "$issuer/", "A2G_OIDC_CLIENT_ID" to clientId)).oidc!!
        assertEquals(issuer, c.issuer)
        assertEquals("$issuer/.well-known/jwks.json", c.jwksUri)
        val custom = ServerConfig.fromEnv(mapOf("A2G_OIDC_ISSUER" to "https://id.example.com/realm", "A2G_OIDC_CLIENT_ID" to "c", "A2G_OIDC_JWKS_URI" to "https://id.example.com/realm/certs")).oidc!!
        assertEquals("https://id.example.com/realm/certs", custom.jwksUri)
        try { ServerConfig.fromEnv(mapOf("A2G_OIDC_ISSUER" to "http://id.example.com", "A2G_OIDC_CLIENT_ID" to "c")); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("https")) }
    }
}
