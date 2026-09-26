package craftpanel.systemtest.harness

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.opentest4j.TestAbortedException
import java.util.concurrent.TimeUnit

/**
 * Resolves live Modrinth version metadata for system-test fixtures.
 *
 * Modrinth is an external dependency that is occasionally slow or briefly unavailable. A bare
 * `OkHttpClient()` used to inherit OkHttp's 10s read timeout with no retry, so a transient stall on
 * Modrinth's side failed the whole suite (`SocketTimeoutException`). This uses generous timeouts and
 * a few retries with backoff, and — because these tests cannot validate anything without Modrinth —
 * marks the calling test skipped (`TestAbortedException`, which Kotest maps to Ignored) rather than
 * failing CI when Modrinth stays unreachable.
 */
object ModrinthFixture {

    private const val ATTEMPTS = 3

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Modrinth's version list for [projectId] filtered by [loader] + [mcVersion]. Aborts the current
     * test (skip) if Modrinth stays unreachable for every attempt.
     */
    fun versions(projectId: String, loader: String, mcVersion: String): JsonArray {
        val url = "https://api.modrinth.com/v2/project/$projectId/version" +
            "?loaders=%5B%22$loader%22%5D&game_versions=%5B%22$mcVersion%22%5D"
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                val body = client.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { it.body.string() }
                return JsonParser.parseString(body).asJsonArray
            } catch (e: Exception) {
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw TestAbortedException("Modrinth unreachable for '$projectId' after $ATTEMPTS attempts", lastError)
    }

    /** Aborts the current test (skip) when Modrinth is unreachable, for tests that talk to it indirectly. */
    fun assumeReachable() {
        versions("lithium", "fabric", "1.21.4")
    }
}
