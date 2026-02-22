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
 * Supports:
 * - Static user-generated API key login.
 * - OAuth authorization-code + PKCE exchange, yielding scoped API credentials.
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
     * Authenticate with itch.io using a static API key.
     * Validates the key by fetching the user's profile, then stores credentials.
     *
     * @param context Android context
     * @param apiKey The API key from https://itch.io/user/settings/api-keys
     * @return Result containing ItchCredentials on success
     */
    suspend fun authenticateWithApiKey(context: Context, apiKey: String): Result<ItchCredentials> {
        return try {
            Timber.tag("Itch").i("Starting itch.io authentication with API key...")

            if (apiKey.isBlank()) {
                return Result.failure(Exception("API key is empty"))
            }

            // Validate the key by fetching the user's profile
            Timber.tag("Itch").d("Validating API key by fetching profile...")
            val profileResult = fetchProfile(apiKey)

            if (profileResult.isFailure) {
                val error = profileResult.exceptionOrNull()
                Timber.tag("Itch").e(error, "Failed to validate API key: ${error?.message}")
                return Result.failure(error ?: Exception("API key validation failed"))
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
     * Fetch the user's profile from itch.io API to validate the API key
     * and extract user information.
     */
    private suspend fun fetchProfile(apiKey: String): Result<ItchCredentials> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${ItchConstants.ITCH_API_BASE_URL}/profile")
                    .header("Authorization", "Bearer $apiKey")
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
                        apiKey = apiKey,
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
     * Get stored credentials. Itch.io API keys do not expire, so no refresh is needed.
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

    /**
     * Exchange OAuth authorization code for API key using PKCE.
     * This matches the itch.io desktop app's OAuth flow.
     *
     * @param context Android context
     * @param code Authorization code from OAuth redirect
     * @param codeVerifier PKCE code verifier
     * @param redirectUri OAuth redirect URI used in authorization
     * @return Result containing ItchCredentials on success
     */
    suspend fun exchangeOAuthCode(
        context: Context,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<ItchCredentials> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.tag("Itch").d("Exchanging OAuth code for API key...")

                // Build OAuth token exchange request (matches go-itchio implementation)
                val requestBody = okhttp3.FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("code_verifier", codeVerifier)
                    .add("redirect_uri", redirectUri)
                    .add("client_id", ItchConstants.OAUTH_CLIENT_ID)
                    .build()

                val request = Request.Builder()
                    .url("${ItchConstants.ITCH_API_BASE_URL}/oauth/token")
                    .post(requestBody)
                    .build()

                Timber.tag("Itch").d("Sending OAuth token exchange request...")
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Timber.tag("Itch").e("OAuth token exchange failed: HTTP ${response.code} - $errorBody")
                        return@withContext Result.failure(
                            Exception("Failed to exchange OAuth code: HTTP ${response.code}")
                        )
                    }

                    val responseBody = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))

                    val json = JSONObject(responseBody)

                    if (json.has("errors")) {
                        val errors = json.getJSONArray("errors")
                        val errorMsg = if (errors.length() > 0) errors.getString(0) else "Unknown error"
                        return@withContext Result.failure(Exception("OAuth error: $errorMsg"))
                    }

                    // OAuth responses can have different formats depending on flow/client
                    // 1. key.key format (traditional API key response)
                    // 2. accessToken format (OAuth2 token response)
                    var apiKey: String? = null
                    
                    try {
                        // Try traditional key format first
                        val keyObj = json.optJSONObject("key")
                        if (keyObj != null) {
                            apiKey = keyObj.optString("key")
                            if (!apiKey.isNullOrEmpty()) {
                                Timber.tag("Itch").d("Found API key in 'key.key' format")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("Itch").w(e, "Failed to extract key.key format")
                    }
                    
                    // Try OAuth2 access token format
                    if (apiKey.isNullOrEmpty()) {
                        apiKey = json.optString("accessToken")
                        if (!apiKey.isNullOrEmpty()) {
                            Timber.tag("Itch").d("Found API key in 'accessToken' format")
                        }
                    }
                    
                    // Fallback: try direct 'key' field
                    if (apiKey.isNullOrEmpty()) {
                        apiKey = json.optString("key")
                        if (!apiKey.isNullOrEmpty()) {
                            Timber.tag("Itch").d("Found API key in direct 'key' format")
                        }
                    }
                    
                    if (apiKey.isNullOrEmpty()) {
                        val keys = json.keys().asSequence().joinToString(", ")
                        Timber.tag("Itch").e("OAuth response keys: $keys")
                        Timber.tag("Itch").e("Full response: ${responseBody.take(200)}")
                        return@withContext Result.failure(
                            Exception("No API key found in OAuth response. Available fields: $keys")
                        )
                    }

                    // Extract API key from response
                    // Now validate the key and get user profile
                    val profileResult = fetchProfile(apiKey)
                    if (profileResult.isFailure) {
                        return@withContext Result.failure(
                            profileResult.exceptionOrNull() ?: Exception("Failed to fetch profile")
                        )
                    }

                    val credentials = profileResult.getOrNull()!!
                    saveCredentials(context, credentials)

                    Timber.tag("Itch").i("OAuth authentication successful for user: ${credentials.username}")
                    Result.success(credentials)
                }
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "OAuth code exchange exception")
                Result.failure(e)
            }
        }
    }

    private fun saveCredentials(context: Context, credentials: ItchCredentials) {
        val json = JSONObject().apply {
            put("api_key", credentials.apiKey)
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
                apiKey = json.optString("api_key", json.optString("access_token", "")),
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
