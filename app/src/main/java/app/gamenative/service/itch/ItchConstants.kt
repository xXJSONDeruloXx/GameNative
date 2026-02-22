package app.gamenative.service.itch

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.SecureRandom

/**
 * Constants for itch.io integration.
 *
 * Current auth strategy:
 * - Primary: user-generated API key (full access for library + downloads)
 * - Secondary (hidden in UI): OAuth 2.0 authorization code + PKCE callback
 *   kept for future partnership/scope changes.
 *
 * NOTE: OAuth constants remain for the retained (hidden) OAuth path.
 * You must register an OAuth application on itch.io and fill in your
 * client ID below if OAuth is re-enabled in UI.
 */
object ItchConstants {
    // itch.io OAuth Configuration
    // TODO: Replace with your registered OAuth application's client ID from
    //       https://itch.io/user/settings/oauth-apps
    const val OAUTH_CLIENT_ID = "9090686bf0ccef25a3be8513b69f50af"

    // Redirect URI for OAuth callback - must match OAuth app settings.
    const val ITCH_REDIRECT_URI = "gamenative://itch/callback"
    
    // itch.io base URLs
    const val ITCH_BASE_URL = "https://itch.io"

    // itch.io API base URL (used with Authorization header)
    const val ITCH_API_BASE_URL = "https://api.itch.io"
    const val API_BASE = ITCH_API_BASE_URL

    // OAuth authorization endpoint
    const val ITCH_AUTH_URL = "$ITCH_BASE_URL/user/oauth"

    // Scopes required for current GameNative API usage:
    //  - profile:me     -> /profile
    //  - profile:owned  -> /profile/owned-keys
    const val ITCH_SCOPES = "profile:me profile:owned"

    /**
     * Generates an OAuth state parameter for CSRF protection.
     */
    fun generateOAuthState(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

    /**
     * Builds the OAuth authorization URL for authorization code flow with PKCE.
     */
    fun buildAuthorizationCodeUrl(state: String, codeChallenge: String): String {
        return Uri.parse(ITCH_AUTH_URL)
            .buildUpon()
            .appendQueryParameter("client_id", OAUTH_CLIENT_ID)
            .appendQueryParameter("scope", ITCH_SCOPES)
            .appendQueryParameter("redirect_uri", ITCH_REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /**
     * URL to itch.io API keys page where users can generate static API keys.
     * This is the primary end-user login path in current builds.
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
