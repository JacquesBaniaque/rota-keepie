package org.jb.keepie

import io.javalin.Javalin
import io.javalin.core.util.RouteOverviewPlugin
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.text.CharacterPredicates
import org.apache.commons.text.RandomStringGenerator
import java.time.Duration

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val secretsConfig = SecretsConfig(mapOf("my_secret" to setOf("http://localhost:7000/receive/secret")))
    val secretsVault = SecretsVault()

    val httpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(10))
        .build()
    val app = Javalin.create { config ->
        config.registerPlugin(RouteOverviewPlugin("routes"))
    }
        .get("/") { ctx -> ctx.result("Hello World") }
        .post("/request/secret") { ctx ->
            val request = ctx.body<SecretRequest>()
            if (!secretsConfig.secretNameToReceivers.containsKey(request.secretName)) {
                logger.warn { "Secret not available: '${request.secretName}'" }
                ctx.status(404)
                return@post
            }
            val secretReceivers = secretsConfig.secretNameToReceivers[request.secretName]!!
            if (!secretReceivers.contains(request.sendTo)) {
                logger.warn { "Secret '${request.secretName}' can't be sent to '${request.sendTo}" }
                ctx.status(403)
                return@post
            }

            val req = Request.Builder()
                .post(secretsVault.getSecret(request.secretName).toRequestBody("plain/text".toMediaType()))
                .url(request.sendTo)
                .build()
            logger.info { "Sending '${request.secretName}' to '${request.sendTo}'..." }
            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    logger.info { "OK" }
                    ctx.status(200)
                } else {
                    logger.error { "Failed" }
                    ctx.status(500)
                }
                return@use
            }
            ctx.result("OK")
        }
        .post("/receive/secret") { _ ->
            logger.info { "Received secret!" }
        }
        .start(7000)
}

data class SecretRequest(val secretName: String, val sendTo: String)

data class SecretsConfig(val secretNameToReceivers: Map<String, Set<String>>)

class SecretsVault {
    private val generator = RandomStringGenerator.Builder()
        .withinRange(charArrayOf('0', 'z'))
        .filteredBy(CharacterPredicates.LETTERS, CharacterPredicates.DIGITS)
        .build()

    fun getSecret(secretName: String): String {
        return generator.generate(32)
    }
}