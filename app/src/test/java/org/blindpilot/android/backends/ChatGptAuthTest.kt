package org.blindpilot.android.backends

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.blindpilot.android.core.Account
import org.blindpilot.android.core.Cancellation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ChatGptAuthTest {
    private val clientId = "oaiapp_test"
    private val now = System.currentTimeMillis() / 1000
    private fun claims() = JSONObject().put("iss", ChatGptAuth.ISSUER).put("sub", "user1")
        .put("aud", clientId).put("nonce", "nonce").put("iat", now).put("exp", now + 3600)
        .put("email", "person@example.com")
    private fun signed(claims: JSONObject, algorithm: String = "RS256"): String {
        val header = encode(JSONObject().put("kid", "test-key").put("alg", algorithm).toString().toByteArray())
        val body = encode(claims.toString().toByteArray())
        val message = "$header.$body"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keyPair.private); update(message.toByteArray()) }.sign()
        return "$message.${encode(signature)}"
    }
    private fun jwks(): JSONObject {
        val publicKey = keyPair.public as RSAPublicKey
        fun unsigned(number: BigInteger) = number.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "test-key").put("kty", "RSA")
            .put("alg", "RS256").put("use", "sig").put("n", encode(unsigned(publicKey.modulus)))
            .put("e", encode(unsigned(publicKey.publicExponent)))))
    }
    private fun tokens() = JSONObject().put("access_token", "access").put("refresh_token", "refresh")
        .put("scope", "openid offline_access resource.invoke chatgpt.tokens.use.direct").put("token_type", "Bearer").put("expires_in", 3600)

    @Test fun pkceUsesKnownS256VectorAndFreshSecureValues() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = ChatGptAuth.randomValue(64)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        assertNotEquals(first, ChatGptAuth.randomValue(64))
    }

    @Test fun authorizationSeparatesRegistrationFromReturningAccount() {
        fun url(previous: JSONObject?) = ChatGptAuth.authorizationUrl("${ChatGptAuth.ISSUER}/api/accounts/authorize",
            "http://127.0.0.1:1234/auth/callback", "urn:uuid:host", "state", "nonce", "verifier", previous).toHttpUrl()
        assertEquals("dynamic_agent_client", url(null).queryParameter("client_id"))
        assertEquals("BlindPilot", url(null).queryParameter("agent_name_hint"))
        assertEquals("S256", url(null).queryParameter("code_challenge_method"))
        val returning = url(JSONObject().put("client_id", clientId).put("email", "person@example.com").put("id_token", "old-token"))
        assertEquals(clientId, returning.queryParameter("client_id"))
        assertEquals("old-token", returning.queryParameter("id_token_hint"))
        assertNull(returning.queryParameter("agent_name_hint"))
    }

    @Test fun stateErrorsMissingAndChangedClientIdsCannotRedeemCode() {
        fun callback(query: String, expected: String? = null) = ChatGptAuth.validateCallback("http://127.0.0.1:1234/auth/callback?$query", "state", expected)
        assertEquals(Pair("code", clientId), callback("state=state&code=code&client_id=$clientId"))
        assertEquals(Pair("code", clientId), callback("state=state&code=code", clientId))
        for (query in listOf("code=code&client_id=$clientId", "state=wrong&code=code&client_id=$clientId",
            "state=state&error=access_denied", "state=state&code=code", "state=state&code=code&client_id=dynamic_agent_client",
            "state=state&state=wrong&code=code&client_id=$clientId")) {
            assertThrows(IOException::class.java) { callback(query) }
        }
        assertThrows(IOException::class.java) { callback("state=state&code=code&client_id=another", clientId) }
    }

    @Test fun discoveryCannotSendCredentialsToAnotherOrigin() {
        assertEquals("auth.openai.com", ChatGptAuth.trustedEndpoint(ChatGptAuth.TOKEN_ENDPOINT).host)
        for (url in listOf("http://auth.openai.com/token", "https://auth.openai.com.attacker.test/token",
            "https://user@auth.openai.com/token", "https://auth.openai.com:444/token", "https://auth.openai.com/token#fragment")) {
            assertThrows(IOException::class.java) { ChatGptAuth.trustedEndpoint(url) }
        }
    }

    @Test fun idTokenChecksSignatureIssuerAudienceTimeNonceAndKeyId() {
        val good = claims()
        assertEquals("user1", ChatGptAuth.verifyIdentity(signed(good), clientId, "nonce", jwks(), now).getString("sub"))
        for ((name, value) in listOf("iss" to "https://attacker.test", "aud" to "another", "nonce" to "wrong", "exp" to now - 10,
            "iat" to now + 20, "nbf" to now + 20, "sub" to "")) {
            assertThrows(IOException::class.java) { ChatGptAuth.verifyIdentity(signed(JSONObject(good.toString()).put(name, value)), clientId, "nonce", jwks(), now) }
        }
        assertThrows(IOException::class.java) { ChatGptAuth.verifyIdentity(signed(good, "none"), clientId, "nonce", jwks(), now) }
        val token = signed(good)
        val changedBody = encode(JSONObject(good.toString()).put("sub", "attacker").toString().toByteArray())
        assertThrows(IOException::class.java) { ChatGptAuth.verifyIdentity("${token.substringBefore('.')}.$changedBody.${token.substringAfterLast('.')}", clientId, "nonce", jwks(), now) }
        assertThrows(IOException::class.java) { ChatGptAuth.verifyIdentity(token, clientId, "nonce", JSONObject().put("keys", JSONArray()), now) }
        val multiple = claims().put("aud", JSONArray().put(clientId).put("other"))
        assertThrows(IOException::class.java) { ChatGptAuth.verifyIdentity(signed(multiple), clientId, "nonce", jwks(), now) }
        assertEquals("user1", ChatGptAuth.verifyIdentity(signed(multiple.put("azp", clientId)), clientId, "nonce", jwks(), now).getString("sub"))
    }

    @Test fun identitySignInAloneCannotEnablePlanAndTokensMustBeRenewable() {
        assertEquals("refresh", ChatGptAuth.credentials(tokens(), clientId, "host", claims()).getString("refresh_token"))
        for (change in listOf("scope" to "openid profile email", "token_type" to "Basic", "expires_in" to 0, "refresh_token" to "", "access_token" to "")) {
            assertThrows(IOException::class.java) { ChatGptAuth.credentials(tokens().put(change.first, change.second), clientId, "host", claims()) }
        }
    }

    @Test fun loopbackSignInAndRefreshKeepHostAndRotatingCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var nonce = ""
        var refreshed = 0
        var saved: Account? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> JSONObject().put("issuer", ChatGptAuth.ISSUER)
                    .put("authorization_endpoint", "${ChatGptAuth.ISSUER}/api/accounts/authorize")
                    .put("token_endpoint", ChatGptAuth.TOKEN_ENDPOINT).put("jwks_uri", "${ChatGptAuth.ISSUER}/.well-known/jwks.json")
                "/.well-known/jwks.json" -> jwks()
                else -> {
                    val body = request.body as okhttp3.FormBody
                    val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
                    assertEquals(clientId, fields["client_id"])
                    assertEquals(ChatGptAuth.RESOURCE, fields["resource"])
                    if (fields["grant_type"] == "refresh_token") {
                        refreshed++
                        assertEquals("refresh", fields["refresh_token"])
                        assertFalse(fields.containsKey("scope"))
                        tokens().put("refresh_token", "rotated").put("access_token", "new-access")
                    } else {
                        assertEquals("code", fields["code"])
                        assertTrue(fields["redirect_uri"]!!.startsWith("http://127.0.0.1:"))
                        tokens().put("id_token", signed(claims().put("nonce", nonce)))
                    }
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = ChatGptAuth(context, { saved = it }, http)
        val browser = Executors.newSingleThreadExecutor()
        try {
            var host = ""
            val result = auth.signIn(Cancellation(), { value ->
                val url = value.toHttpUrl()
                nonce = url.queryParameter("nonce")!!
                host = url.queryParameter("ext_agent_host_id")!!
                val redirect = url.queryParameter("redirect_uri")!!.toHttpUrl()
                browser.submit {
                    Socket("127.0.0.1", redirect.port).use { socket ->
                        socket.getOutputStream().write(("GET /auth/callback?code=code&state=${url.queryParameter("state")}&client_id=$clientId HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").toByteArray())
                        socket.getInputStream().readBytes()
                    }
                }
            })
            assertEquals("access", result.accessToken)
            assertEquals(host, JSONObject(result.credential).getString("ext_agent_host_id"))
            val expired = JSONObject(result.credential).put("expires_at", 0)
            val account = Account(provider = "chatgpt_plan", credential = expired.toString())
            assertEquals("new-access", auth.accessToken(account, Cancellation()))
            assertEquals("rotated", JSONObject(saved!!.credential).getString("refresh_token"))
            assertEquals(account.id, saved!!.id)
            assertEquals("new-access", auth.accessToken(account, Cancellation()))
            assertEquals(1, refreshed)
        } finally { browser.shutdown(); assertTrue(browser.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    companion object {
        private val keyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
