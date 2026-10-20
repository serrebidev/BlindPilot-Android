package org.blindpilot.android.backends

import android.content.Context
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blindpilot.android.core.Account
import org.blindpilot.android.core.Cancellation
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Public-client SIWC flow; the browser returns to a listener on this Android device. */
class ChatGptAuth(
    context: Context,
    private val onCredentials: (Account) -> Unit = {},
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    data class AuthResult(val credential: String, val accessToken: String, val label: String)
    private val preferences = context.applicationContext.getSharedPreferences("chatgpt_host", Context.MODE_PRIVATE)
    private val renewed = mutableMapOf<String, String>()

    @Synchronized
    private fun hostId(): String = preferences.getString("id", null) ?: ("urn:uuid:" + UUID.randomUUID()).also {
        check(preferences.edit().putString("id", it).commit()) { "Could not save the ChatGPT host identity." }
    }

    fun signIn(cancellation: Cancellation, openBrowser: (String) -> Unit, account: Account? = null): AuthResult {
        cancellation.check()
        val previous = account?.let { synchronized(this) { JSONObject(renewed[it.id] ?: it.credential) } }
        val configuration = configuration(cancellation)
        val state = randomValue()
        val nonce = randomValue()
        val verifier = randomValue(64)
        val host = hostId()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 500
            cancellation.onCancel { listener.close() }
            val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
            val url = authorizationUrl(configuration.getString("authorization_endpoint"), redirect, host,
                state, nonce, verifier, previous)
            openBrowser(url)
            val callback = awaitCallback(listener, redirect, state, previous?.getString("client_id"), cancellation)
            val tokens = requestJson(configuration.getString("token_endpoint"), cancellation, FormBody.Builder()
                .add("grant_type", "authorization_code").add("client_id", callback.second)
                .add("code", callback.first).add("code_verifier", verifier).add("redirect_uri", redirect)
                .add("resource", RESOURCE).build())
            val identity = verifyIdentity(requiredString(tokens, "id_token"), callback.second, nonce,
                requestJson(configuration.getString("jwks_uri"), cancellation))
            if (previous != null && identity.getString("sub") != previous.getString("subject"))
                throw IOException("ChatGPT returned a different account. Add it as a separate account.")
            val credential = credentials(tokens, callback.second, host, identity)
            return AuthResult(credential.toString(), credential.getString("access_token"),
                identity.optString("email").ifBlank { "ChatGPT" } + " (${callback.second.takeLast(8)})")
        }
    }

    @Synchronized
    fun accessToken(account: Account, cancellation: Cancellation): String {
        cancellation.check()
        val saved = try { JSONObject(renewed[account.id] ?: account.credential) }
        catch (_: Exception) { throw IOException("Continue with ChatGPT to connect this account.") }
        requirePlanScope(saved.optString("scope"))
        if (saved.optLong("expires_at") > System.currentTimeMillis() / 1000 + 60)
            return requiredString(saved, "access_token")
        val tokens = requestJson(TOKEN_ENDPOINT, cancellation, FormBody.Builder()
            .add("grant_type", "refresh_token").add("client_id", requiredString(saved, "client_id"))
            .add("refresh_token", requiredString(saved, "refresh_token")).add("resource", RESOURCE).build())
        val identity = if (tokens.has("id_token")) {
            val configuration = configuration(cancellation)
            verifyIdentity(requiredString(tokens, "id_token"), saved.getString("client_id"), null,
                requestJson(configuration.getString("jwks_uri"), cancellation)).also {
                if (it.getString("sub") != saved.getString("subject")) throw IOException("ChatGPT account identity changed. Sign in again.")
            }
        } else JSONObject().put("sub", saved.getString("subject")).put("email", saved.optString("email"))
        val updated = credentials(tokens, saved.getString("client_id"), saved.getString("ext_agent_host_id"), identity)
        if (!updated.has("id_token")) updated.put("id_token", saved.optString("id_token"))
        val encoded = updated.toString()
        // A rotating refresh token must remain usable even if the caller's persistence fails.
        renewed[account.id] = encoded
        onCredentials(account.copy(credential = encoded))
        return updated.getString("access_token")
    }

    private fun configuration(cancellation: Cancellation): JSONObject =
        requestJson("$ISSUER/.well-known/openid-configuration", cancellation).also { document ->
            if (document.optString("issuer") != ISSUER) throw IOException("Unexpected ChatGPT identity issuer.")
            for (field in listOf("authorization_endpoint", "token_endpoint", "jwks_uri")) trustedEndpoint(requiredString(document, field))
        }

    private fun requestJson(url: String, cancellation: Cancellation, form: FormBody? = null): JSONObject {
        trustedEndpoint(url)
        cancellation.check()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply { if (form != null) post(form) }.build()
        val call = client.newCall(request)
        cancellation.onCancel { call.cancel() }
        call.execute().use { response ->
            cancellation.check()
            if (!response.isSuccessful) throw IOException("ChatGPT sign-in request failed (HTTP ${response.code}). Continue with ChatGPT again if the session expired.")
            val body = response.body ?: throw IOException("ChatGPT returned an empty sign-in response.")
            val buffer = ByteArrayOutputStream()
            val bytes = ByteArray(8192)
            val input = body.byteStream()
            while (true) {
                cancellation.check()
                val count = input.read(bytes)
                if (count < 0) break
                if (buffer.size() + count > 1_048_576) throw IOException("ChatGPT sign-in response was too large.")
                buffer.write(bytes, 0, count)
            }
            return try { JSONObject(buffer.toString("UTF-8")) }
            catch (_: Exception) { throw IOException("ChatGPT returned an invalid sign-in response.") }
        }
    }

    private fun awaitCallback(listener: ServerSocket, redirect: String, state: String, clientId: String?, cancellation: Cancellation): Pair<String, String> {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        while (System.nanoTime() < deadline) {
            cancellation.check()
            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                cancellation.onCancel { socket.close() }
                socket.soTimeout = 3000
                val firstLine = StringBuilder()
                val input = socket.getInputStream()
                while (firstLine.length < 8192) {
                    val value = input.read()
                    if (value < 0 || value == 10) break
                    firstLine.append(value.toChar())
                }
                val parts = firstLine.toString().trimEnd('\r').split(' ')
                val target = parts.getOrNull(1).orEmpty()
                if (parts.size != 3 || parts[0] != "GET" || !target.startsWith("/auth/callback?")) {
                    reply(socket, "404 Not Found", "Return to the ChatGPT sign-in page.")
                    return@use
                }
                val callback = try { validateCallback(redirect + target.removePrefix("/auth/callback"), state, clientId) }
                catch (failure: IOException) {
                    reply(socket, "400 Bad Request", "ChatGPT sign-in could not be verified. Return to BlindPilot and try again.")
                    throw failure
                }
                reply(socket, "200 OK", "ChatGPT sign-in received. Return to BlindPilot to finish connecting.")
                return callback
            }
        }
        throw IOException("ChatGPT sign-in timed out. Continue with ChatGPT to try again.")
    }

    private fun reply(socket: java.net.Socket, status: String, text: String) {
        runCatching { socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${text.toByteArray().size}\r\n\r\n$text").toByteArray()) }
    }

    companion object {
        internal const val ISSUER = "https://auth.openai.com"
        internal const val RESOURCE = "https://api.openai.com/v1"
        internal const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
        private const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        internal fun randomValue(size: Int = 32): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(size).also { SecureRandom().nextBytes(it) })
        internal fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        internal fun trustedEndpoint(value: String): HttpUrl {
            val url = value.toHttpUrl()
            if (url.scheme != "https" || url.host != "auth.openai.com" || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
                throw IOException("Untrusted ChatGPT sign-in endpoint.")
            return url
        }

        internal fun authorizationUrl(endpoint: String, redirect: String, host: String, state: String, nonce: String, verifier: String, previous: JSONObject?): String =
            trustedEndpoint(endpoint).newBuilder().addQueryParameter("client_id", previous?.getString("client_id") ?: "dynamic_agent_client")
                .addQueryParameter("ext_agent_host_id", host).addQueryParameter("redirect_uri", redirect)
                .addQueryParameter("response_type", "code").addQueryParameter("scope", SCOPE)
                .addQueryParameter("resource", RESOURCE).addQueryParameter("state", state).addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", challenge(verifier))
                .apply {
                    if (previous == null) addQueryParameter("agent_name_hint", "BlindPilot")
                    else {
                        previous.optString("id_token").takeIf { it.isNotBlank() }?.let { addQueryParameter("id_token_hint", it) }
                        previous.optString("email").takeIf { it.isNotBlank() }?.let { addQueryParameter("login_hint", it) }
                    }
                }.build().toString()

        internal fun validateCallback(value: String, expectedState: String, expectedClientId: String?): Pair<String, String> {
            val url = value.toHttpUrl()
            for (key in listOf("state", "code", "client_id", "error")) {
                if (url.queryParameterValues(key).size > 1) throw IOException("ChatGPT returned an ambiguous sign-in callback.")
            }
            if (!MessageDigest.isEqual(expectedState.toByteArray(), url.queryParameter("state").orEmpty().toByteArray()))
                throw IOException("ChatGPT sign-in state did not match. Try again.")
            if (url.queryParameter("error") != null) throw IOException("ChatGPT sign-in was declined or interrupted. Continue with ChatGPT to try again.")
            val code = url.queryParameter("code").orEmpty()
            val clientId = url.queryParameter("client_id") ?: expectedClientId.orEmpty()
            if (code.isBlank() || clientId.isBlank() || clientId == "dynamic_agent_client" ||
                (expectedClientId != null && clientId != expectedClientId)) throw IOException("ChatGPT registration was incomplete or changed. Try again.")
            return Pair(code, clientId)
        }

        internal fun verifyIdentity(token: String, clientId: String, nonce: String?, jwks: JSONObject, now: Long = System.currentTimeMillis() / 1000): JSONObject {
            try {
                val parts = token.split('.')
                require(parts.size == 3)
                val decoder = Base64.getUrlDecoder()
                val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
                require(header.getString("alg") == "RS256" && !header.has("crit") && !header.has("b64"))
                val keys = jwks.getJSONArray("keys")
                val matches = (0 until keys.length()).map { keys.getJSONObject(it) }.filter {
                    it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" &&
                        (!it.has("use") || it.optString("use") == "sig") && (!it.has("alg") || it.optString("alg") == "RS256")
                }
                require(matches.size == 1)
                val key = matches.single()
                val modulus = BigInteger(1, decoder.decode(key.getString("n")))
                require(modulus.bitLength() >= 2048)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger(1, decoder.decode(key.getString("e")))))
                require(Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)) }.verify(decoder.decode(parts[2])))
                val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
                require(claims.getString("iss") == ISSUER && requiredString(claims, "sub").isNotBlank())
                val audience = claims.get("aud")
                val audiences = if (audience is JSONArray) (0 until audience.length()).map { audience.getString(it) } else listOf(audience as String)
                require(clientId in audiences)
                require((audiences.size == 1 && !claims.has("azp")) || claims.getString("azp") == clientId)
                require(claims.getLong("exp") > now - 5 && claims.getLong("iat") <= now + 5)
                if (claims.has("nbf")) require(claims.getLong("nbf") <= now + 5)
                if (nonce != null) require(MessageDigest.isEqual(nonce.toByteArray(), claims.getString("nonce").toByteArray()))
                return claims
            } catch (_: Exception) { throw IOException("ChatGPT identity could not be verified. Continue with ChatGPT again.") }
        }

        internal fun credentials(tokens: JSONObject, clientId: String, host: String, identity: JSONObject): JSONObject {
            val scope = requiredString(tokens, "scope")
            requirePlanScope(scope)
            if (!tokens.optString("token_type").equals("Bearer", ignoreCase = true)) throw IOException("ChatGPT returned an unsupported token type.")
            val expires = tokens.optLong("expires_in")
            if (expires <= 0 || expires > 86400) throw IOException("ChatGPT returned an invalid token lifetime.")
            return JSONObject().put("issuer", ISSUER).put("subject", identity.getString("sub")).put("email", identity.optString("email"))
                .put("client_id", clientId).put("ext_agent_host_id", host).put("scope", scope)
                .put("access_token", requiredString(tokens, "access_token")).put("refresh_token", requiredString(tokens, "refresh_token"))
                .put("token_type", "Bearer").put("expires_at", System.currentTimeMillis() / 1000 + expires)
                .apply { if (tokens.has("id_token")) put("id_token", requiredString(tokens, "id_token")) }
        }

        private fun requirePlanScope(scope: String) {
            if (!scope.split(Regex("\\s+")).contains("chatgpt.tokens.use.direct"))
                throw IOException("ChatGPT plan use was not granted. Continue with ChatGPT and allow plan access.")
        }
        private fun requiredString(json: JSONObject, name: String): String = (json.opt(name) as? String)?.takeIf { it.isNotBlank() }
            ?: throw IOException("ChatGPT sign-in response is missing $name.")
    }
}
