package org.telegram.messenger.forkgram

import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI

object ForkTranslate {

    // Should be invoked in thread.
    @JvmStatic
    fun translate(
        fromLanguage: String,
        toLanguage: String,
        userAgents: Array<String>,
        text: CharSequence
    ): Array<String> {
        fun userAgent(): String {
            return userAgents.random()
        }
        fun readResponse(connection: HttpURLConnection): String {
            return connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }
        fun getVqd(): String? {
            val uri = URI("https://duckduckgo.com/?q=translate&ia=web")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent())
            val response = readResponse(connection)
            val start = response.indexOf("vqd=")
            val end = response.indexOf(";", start)
            val substring = response.substring(start + "vqd=".length, end)
            return Regex("[0-9-]+").find(substring)?.groupValues?.getOrNull(0)
        }

        fun fetchTranslate(): Array<String> {
            val uri = URI("https://duckduckgo.com/translation.js?vqd=${ getVqd() }&query=translate&to=${ android.net.Uri.encode(toLanguage) }")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("User-Agent", userAgent())
            connection.setRequestProperty("Content-Type", "application/json")

            connection.doOutput = true
            connection.outputStream.use { it.write(text.toString().toByteArray()) }
            val response = readResponse(connection)
            val obj = JSONObject(JSONTokener(response))
            val source = obj.getString("detected_language")
            val result = obj.getString("translated")
            return arrayOf(result, source)
        }

        return fetchTranslate()
    }

}
