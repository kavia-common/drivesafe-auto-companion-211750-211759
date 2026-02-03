package org.example.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object BackendClient {

    // PUBLIC_INTERFACE
    fun sendRecognizedCommand(baseUrl: String, text: String): BackendResponse {
        /**
         * Sends a recognized voice command to the backend for optional parsing/analytics.
         *
         * Currently posts to:
         *   POST {baseUrl}/api/v1/command
         * with body: text=<urlencoded>
         *
         * Returns: BackendResponse containing status code and response body (best-effort).
         */
        return try {
            val endpoint = URL(baseUrl.trimEnd('/') + "/api/v1/command")
            val conn = endpoint.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")

            val body = "text=" + URLEncoder.encode(text, "UTF-8")
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.use { s ->
                BufferedReader(InputStreamReader(s)).readText()
            } ?: ""

            BackendResponse(code, responseBody)
        } catch (e: Exception) {
            BackendResponse(-1, e.message ?: "network_error")
        }
    }
}

internal data class BackendResponse(val statusCode: Int, val body: String)
