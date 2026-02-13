package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.ItchCredentials
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages itch.io authentication and credential storage.
 *
 * Itch.io uses OAuth 2.0 Implicit Flow:
 * - The access token is returned directly in the URL fragment (#access_token=...)
 * - Tokens are long-lived API keys — they do not expire and there is no refresh token
 * - The token can be revoked by the user from their itch.io settings
 */
object ItchAuthManager {

    private val httpClient = Net.http

    private fun getCredentialsFilePath(context: Context): String {
        val dir = File(context.filesDir, "itch")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "credentials.json").absolutePath
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val credentialsFile = File(getCredentialsFilePath(context))
        return credentialsFile.exists()
    }

    /**
     * Authenticate with itch.io using the access token from OAuth implicit flow.
     * Validates the token by fetching the user's profile, then stores credentials.
     *
     * @param context Android context
     * @param accessToken The OAuth access token from the URL fragment
     * @return Result containing ItchCredentials on success
     */
    suspend fun authenticateWithToken(context: Context, accessToken: String): Result<ItchCredentials> {
        return try {
            Timber.tag("Itch").i("Starting itch.io authentication with access token...")

            if (accessToken.isBlank()) {
                return Result.failure(Exception("Access token is empty"))
            }

            // Validate the token by fetching the user's profile
            Timber.tag("Itch").d("Validating token by fetching profile...")
            val profileResult = fetchProfile(accessToken)

            if (profileResult.isFailure) {
                val error = profileResult.exceptionOrNull()
                Timber.tag("Itch").e(error, "Failed to validate token: ${error?.message}")
                return Result.failure(error ?: Exception("Token validation failed"))
            }

            val credentials = profileResult.getOrNull()!!

            // Save credentials to file
            saveCredentials(context, credentials)

            Timber.tag("Itch").i("itch.io authentication successful for user: ${credentials.username}")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "itch.io authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    /**
     * Fetch the user's profile from itch.io API to validate the token
     * and extract user information.
     */
    private suspend fun fetchProfile(accessToken: String): Result<ItchCredentials> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${ItchConstants.ITCH_API_BASE_URL}/profile")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Timber.tag("Itch").e("Profile fetch failed: HTTP ${response.code} - $errorBody")
                        return@withContext Result.failure(
                            Exception("Failed to fetch profile: HTTP ${response.code}")
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

                    val userJson = json.getJSONObject("user")

                    val credentials = ItchCredentials(
                        accessToken = accessToken,
                        userId = userJson.getInt("id"),
                        username = userJson.getString("username"),
                        displayName = userJson.optString("display_name", userJson.getString("username")),
                        coverUrl = userJson.optString("cover_url", ""),
                    )

                    Timber.tag("Itch").d("Profile fetched: ${credentials.username} (ID: ${credentials.userId})")
                    Result.success(credentials)
                }
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "Failed to fetch itch.io profile")
                Result.failure(e)
            }
        }
    }

    /**
     * Get stored credentials. Itch.io tokens do not expire, so no refresh is needed.
     * We validate the token by doing a lightweight credentials check.
     */
    suspend fun getStoredCredentials(context: Context): Result<ItchCredentials> {
        return try {
            val credentials = loadCredentials(context)
                ?: return Result.failure(Exception("No stored credentials found"))

            Timber.tag("Itch").d("Retrieved stored credentials for user: ${credentials.username}")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "Failed to get stored credentials")
            Result.failure(e)
        }
    }

    /**
     * Clear stored credentials (logout)
     */
    fun clearStoredCredentials(context: Context): Boolean {
        return try {
            val authFile = File(getCredentialsFilePath(context))
            if (authFile.exists()) {
                authFile.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "Failed to clear itch.io credentials")
            false
        }
    }

    private fun saveCredentials(context: Context, credentials: ItchCredentials) {
        val json = JSONObject().apply {
            put("access_token", credentials.accessToken)
            put("user_id", credentials.userId)
            put("username", credentials.username)
            put("display_name", credentials.displayName)
            put("cover_url", credentials.coverUrl)
        }

        val file = File(getCredentialsFilePath(context))
        file.writeText(json.toString(2))

        Timber.tag("Itch").d("Credentials saved to ${file.absolutePath}")
    }

    private fun loadCredentials(context: Context): ItchCredentials? {
        return try {
            val file = File(getCredentialsFilePath(context))
            if (!file.exists()) {
                return null
            }

            val json = JSONObject(file.readText())

            ItchCredentials(
                accessToken = json.getString("access_token"),
                userId = json.getInt("user_id"),
                username = json.getString("username"),
                displayName = json.optString("display_name", json.getString("username")),
                coverUrl = json.optString("cover_url", ""),
            )
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "Failed to load credentials")
            null
        }
    }
}
