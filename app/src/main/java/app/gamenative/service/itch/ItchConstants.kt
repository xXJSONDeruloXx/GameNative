package app.gamenative.service.itch

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.SecureRandom

/**
 * Constants for itch.io integration.
 *
 * Itch.io uses OAuth 2.0 Authorization Code Flow with PKCE:
 * - More secure than implicit flow
 * - Returns an API key with full scopes (including game:view:uploads)
 * - This matches the authentication used by the itch.io desktop app
 * - Register your OAuth app at https://itch.io/user/settings/oauth-apps
 *
 * NOTE: You must register an OAuth application on itch.io and fill in your
 * client ID below. The redirect URI must match your OAuth app settings.
 */
object ItchConstants {
    // itch.io OAuth Configuration
    // TODO: Replace with your registered OAuth application's client ID from
    //       https://itch.io/user/settings/oauth-apps
    const val OAUTH_CLIENT_ID = "9090686bf0ccef25a3be8513b69f50af"
    
    // Legacy client ID for backwards compatibility
    const val ITCH_CLIENT_ID = OAUTH_CLIENT_ID

    // Redirect URI for OAuth callback - using OOB (out-of-band) which doesn't require app registration
    const val ITCH_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
    
    // itch.io base URLs
    const val ITCH_BASE_URL = "https://itch.io"

    // itch.io API base URL (used with Authorization header)
    const val ITCH_API_BASE_URL = "https://api.itch.io"
    const val API_BASE = ITCH_API_BASE_URL

    // OAuth authorization endpoint
    const val ITCH_AUTH_URL = "$ITCH_BASE_URL/user/oauth"

    // Scopes we request:
    //  - profile:me  — view the user's public profile
    const val ITCH_SCOPES = "profile:me"

    /**
     * Builds the OAuth authorization URL for authorization code flow with PKCE.
     * The user will authorize the app and be redirected back with an authorization code.
     */
    val ITCH_AUTH_LOGIN_URL: String
        get() = "$ITCH_AUTH_URL?" +
            "client_id=$OAUTH_CLIENT_ID" +
            "&scope=${Uri.encode(ITCH_SCOPES)}" +
            "&redirect_uri=${Uri.encode(ITCH_REDIRECT_URI)}" +
            "&response_type=token"

    /**
     * Builds a full OAuth login URL with a fresh state parameter for CSRF protection.
     * @return Pair of (full auth URL, state) — store state and validate it on redirect.
     */
    fun loginUrlWithState(): Pair<String, String> {
        val state = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val url = "$ITCH_AUTH_LOGIN_URL&state=${Uri.encode(state)}"
        return url to state
    }

    /**
     * Simple login URL without state for manual token entry flow.
     * User opens this in browser, completes OAuth, then copies token manually.
     */
    fun loginUrl(): String = ITCH_AUTH_LOGIN_URL
    
    /**
     * URL to itch.io API keys page where users can generate static API keys.
     * These keys have full API access (unlike OAuth tokens with limited scopes).
     */
    const val ITCH_API_KEYS_URL = "https://itch.io/user/settings/api-keys"

    /**
     * Gets the install path for an itch.io game
     */
    fun getGameInstallPath(context: Context, gameName: String): String {
        val sanitizedName = gameName
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .replace(" ", "_")
            .take(100)

        val baseDir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir(null)
        } else {
            context.filesDir
        }

        return File(baseDir, "itch/games/$sanitizedName").absolutePath
    }
}
