package com.anything2game.server.api

import com.anything2game.server.config.OidcConfig
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Verifies an OIDC **access token** (a JWT) and maps it to `user:<sub>`. Works with any provider that publishes a JWKS
 * (Cognito, Keycloak, Google, Auth0, ...); no cloud SDK, only HTTPS to the key set.
 *
 * A token is accepted only if all of this holds, otherwise the caller gets 401:
 *  - the signature verifies with a key of the issuer's JWKS, algorithm RS256 only (so `none` and HMAC "key confusion" tokens fail);
 *  - `iss` equals the configured issuer; `exp` is in the future and `nbf` (if present) in the past (30 s of clock skew);
 *  - `token_use` is `access` when present (a Cognito ID token is refused);
 *  - `client_id` (Cognito access tokens) equals the configured client id, or, when there is no `client_id`, `aud` contains it;
 *  - `sub` is present.
 */
class OidcJwtAuthenticator(
    private val issuer: String,
    private val clientId: String,
    jwkSource: JWKSource<SecurityContext>,
) : Authenticator {
    private val log = LoggerFactory.getLogger(OidcJwtAuthenticator::class.java)

    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
        jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType.JWT, JOSEObjectType("at+jwt"), null)
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier<SecurityContext>(
            JWTClaimsSet.Builder().issuer(issuer).build(),
            setOf("exp", "iss", "sub"),
        ).apply { maxClockSkew = 30 }
    }

    override fun authenticate(authorizationHeader: String?): Principal? {
        val token = bearerToken(authorizationHeader, CompositeAuthenticator.MAX_JWT_LENGTH) ?: return null
        val claims = try {
            // process() verifies the signature first and refuses unsigned (alg none) tokens before looking at any claim.
            processor.process(token, null)
        } catch (e: Exception) {
            // Class name only: the message can echo token content.
            log.debug("token refused: {}", e.javaClass.simpleName)
            return null
        }
        val ok = try {
            val use = claims.getStringClaim("token_use")
            val client = claims.getStringClaim("client_id")
            (use == null || use == "access") && (if (client != null) client == clientId else clientId in claims.audience.orEmpty())
        } catch (e: Exception) {
            false
        }
        val sub = claims.subject
        if (!ok || sub.isNullOrEmpty() || sub.length > MAX_SUB_LENGTH || sub.any { it.isISOControl() }) return null
        return Principal("user:$sub", PrincipalKind.USER)
    }

    companion object {
        private const val MAX_SUB_LENGTH = 200

        /** The production verifier: the issuer's key set is fetched over HTTPS, cached, refreshed when an unknown key id shows up (rate limited). */
        fun fromConfig(config: OidcConfig): OidcJwtAuthenticator {
            val source = JWKSourceBuilder.create<SecurityContext>(URI(config.jwksUri).toURL(), DefaultResourceRetriever(2_000, 2_000, 256 * 1024))
                .retrying(true)
                .build()
            return OidcJwtAuthenticator(config.issuer, config.clientId, source)
        }
    }
}
