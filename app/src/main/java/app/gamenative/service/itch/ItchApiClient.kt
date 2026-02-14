package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.ItchGame
import app.gamenative.enums.AppType
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * API client for itch.io REST API.
 *
 * All endpoints use Bearer token authentication.
 * Docs: https://itch.io/docs/api/serverside
 */
object ItchApiClient {

    private val httpClient = Net.http

    /**
     * Fetch all owned game keys (paginated) from itch.io.
     * Endpoint: GET /profile/owned-keys
     *
     * @param context Android context for credential access
     * @return Result containing list of ItchGames
     */
    suspend fun getOwnedGames(context: Context): Result<List<ItchGame>> {
        return withContext(Dispatchers.IO) {
            try {
                val credentials = ItchAuthManager.getStoredCredentials(context)
                if (credentials.isFailure) {
                    return@withContext Result.failure(
                        credentials.exceptionOrNull() ?: Exception("No credentials")
                    )
                }

                val apiKey = credentials.getOrNull()!!.apiKey
                val allGames = mutableListOf<ItchGame>()
                var page = 1
                var hasMore = true

                while (hasMore) {
                    Timber.tag("Itch").d("Fetching owned keys page $page...")

                    val request = Request.Builder()
                        .url("${ItchConstants.ITCH_API_BASE_URL}/profile/owned-keys?page=$page")
                        .header("Authorization", apiKey)
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: "Unknown error"
                            Timber.tag("Itch").e("Failed to fetch owned keys: HTTP ${response.code} - $errorBody")
                            return@withContext Result.failure(
                                Exception("Failed to fetch owned games: HTTP ${response.code}")
                            )
                        }

                        val responseBody = response.body?.string()
                            ?: return@withContext Result.failure(Exception("Empty response"))

                        val json = JSONObject(responseBody)

                        if (json.has("errors")) {
                            val errors = json.getJSONArray("errors")
                            val errorMsg = if (errors.length() > 0) errors.getString(0) else "Unknown error"
                            return@withContext Result.failure(Exception("API error: $errorMsg"))
                        }

                        val ownedKeys = json.optJSONArray("owned_keys")
                        if (ownedKeys == null || ownedKeys.length() == 0) {
                            hasMore = false
                            return@use
                        }

                        for (i in 0 until ownedKeys.length()) {
                            val keyObj = ownedKeys.getJSONObject(i)
                            val gameObj = keyObj.optJSONObject("game") ?: continue
                            val downloadKeyId = keyObj.optInt("id", 0)

                            val game = parseGameObject(gameObj, downloadKeyId)
                            if (game != null) {
                                allGames.add(game)
                            }
                        }

                        // itch.io uses page-based pagination; if we got fewer results,
                        // or the per_page default is 50 and we got less, we're done.
                        val perPage = json.optInt("per_page", 50)
                        if (ownedKeys.length() < perPage) {
                            hasMore = false
                        } else {
                            page++
                        }
                    }
                }

                Timber.tag("Itch").i("Fetched ${allGames.size} owned games from itch.io")
                Result.success(allGames)
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "Failed to fetch owned games")
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch a single game's details by ID.
     * Endpoint: GET /games/:id
     * (This is a public endpoint but we still pass auth for consistency)
     */
    suspend fun getGameById(context: Context, gameId: Int): Result<ItchGame?> {
        return withContext(Dispatchers.IO) {
            try {
                val credentials = ItchAuthManager.getStoredCredentials(context)
                val apiKey = credentials.getOrNull()?.apiKey ?: ""

                val requestBuilder = Request.Builder()
                    .url("${ItchConstants.ITCH_API_BASE_URL}/games/$gameId")
                    .get()

                if (apiKey.isNotEmpty()) {
                    requestBuilder.header("Authorization", "Bearer $apiKey")
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Failed to fetch game $gameId: HTTP ${response.code}")
                        )
                    }

                    val responseBody = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))

                    val json = JSONObject(responseBody)
                    val gameObj = json.optJSONObject("game")
                        ?: return@withContext Result.success(null)

                    Result.success(parseGameObject(gameObj, 0))
                }
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "Failed to fetch game by ID: $gameId")
                Result.failure(e)
            }
        }
    }

    /**
     * Parse a JSON game object from the itch.io API into an ItchGame entity.
     */
    private fun parseGameObject(gameObj: JSONObject, downloadKeyId: Int): ItchGame? {
        return try {
            val id = gameObj.optInt("id", 0)
            if (id == 0) return null

            val title = gameObj.optString("title", "").trim()
            if (title.isEmpty() || title == "null") return null

            val classification = gameObj.optString("classification", "game")

            ItchGame(
                id = id,
                title = title,
                url = gameObj.optString("url", ""),
                coverUrl = gameObj.optString("cover_url", ""),
                shortText = gameObj.optString("short_text", ""),
                developer = gameObj.optJSONObject("user")?.optString("username", "") ?: "",
                pWindows = gameObj.optBoolean("p_windows", false),
                pLinux = gameObj.optBoolean("p_linux", false),
                pOsx = gameObj.optBoolean("p_osx", false),
                pAndroid = gameObj.optBoolean("p_android", false),
                minPrice = gameObj.optInt("min_price", 0),
                classification = classification,
                createdAt = gameObj.optString("created_at", ""),
                type = if (classification == "game") AppType.game else AppType.tool,
                downloadKeyId = downloadKeyId,
            )
        } catch (e: Exception) {
            Timber.tag("Itch").w(e, "Failed to parse game object")
            null
        }
    }
}
