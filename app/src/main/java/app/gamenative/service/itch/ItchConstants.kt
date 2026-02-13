package app.gamenative.service.itch

import android.net.Uri
import java.security.SecureRandom

/**
 * Constants for itch.io integration.
 *
 * Itch.io uses OAuth 2.0 Implicit Flow:
 * - The access token is returned directly in the URL fragment (#access_token=...)
 * - Tokens are long-lived API keys (no expiry, no refresh token)
 * - Register your OAuth app at https://itch.io/user/settings/oauth-apps
 *
 * NOTE: You must register an OAuth application on itch.io and fill in your
 * client ID below. The redirect URI must match your OAuth app settings.
 */
object ItchConstants {
    // itch.io OAuth Configuration
    // TODO: Replace with your registered OAuth application's client ID from
    //       https://itch.io/user/settings/oauth-apps
    const val ITCH_CLIENT_ID = "TODO_REGISTER_OAUTH_APP"

    // Redirect URI — using out-of-band so the WebView can show the token on a page we control
    // This matches the itch.io OOB pattern; the WebView will extract it from the fragment.
    const val ITCH_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

    // itch.io API base URL (used with Bearer token)
    const val ITCH_API_BASE_URL = "https://api.itch.io"

    // OAuth authorization endpoint
    const val ITCH_AUTH_URL = "https://itch.io/user/oauth"

    // Scopes we request:
    //  - profile:me  — view the user's public profile
    //  - profile:owned — list games the user has purchased or claimed
    const val ITCH_SCOPES = "profile:me profile:owned"

    /**
     * Builds the OAuth authorization URL.
     * Itch.io uses the Implicit Flow (response_type=token), so the access token
     * is returned in the redirect URL's fragment.
     */
    val ITCH_AUTH_LOGIN_URL: String
        get() = "$ITCH_AUTH_URL?" +
            "client_id=$ITCH_CLIENT_ID" +
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
}
