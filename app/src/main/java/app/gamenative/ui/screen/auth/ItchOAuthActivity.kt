package app.gamenative.ui.screen.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.service.itch.ItchConstants
import app.gamenative.ui.component.dialog.AuthWebViewDialog
import app.gamenative.ui.theme.PluviaTheme
import timber.log.Timber

/**
 * Itch.io OAuth Activity that hosts AuthWebViewDialog for the OAuth Implicit Flow.
 *
 * Itch.io uses the Implicit Flow (response_type=token), which returns the access token
 * directly in the redirect URL's fragment:
 *   urn:ietf:wg:oauth:2.0:oob#access_token=TOKEN&state=STATE
 *
 * Since itch.io uses OOB (out-of-band) redirect, the token is shown on an itch.io page.
 * We extract it via JavaScript from the page body when the page finishes loading.
 *
 * Uses a per-session state parameter for CSRF protection.
 */
class ItchOAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_ERROR = "error"
        private const val SAVED_OAUTH_STATE = "oauth_state"
        private const val SAVED_AUTH_URL = "auth_url"
    }

    private var oauthState: String? = null
    private var initialAuthUrl: String? = null
    private var hasFinished: Boolean = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        oauthState?.let { outState.putString(SAVED_OAUTH_STATE, it) }
        initialAuthUrl?.let { outState.putString(SAVED_AUTH_URL, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (authUrl, state) = if (savedInstanceState != null) {
            val savedState = savedInstanceState.getString(SAVED_OAUTH_STATE)
            val savedUrl = savedInstanceState.getString(SAVED_AUTH_URL)
            if (savedState != null && savedUrl != null) {
                savedUrl to savedState
            } else {
                ItchConstants.loginUrlWithState()
            }
        } else {
            ItchConstants.loginUrlWithState()
        }
        oauthState = state
        initialAuthUrl = authUrl

        setContent {
            PluviaTheme {
                AuthWebViewDialog(
                    isVisible = true,
                    url = authUrl,
                    onDismissRequest = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onUrlChange = { currentUrl: String ->
                        // Check if the URL contains a fragment with the access token
                        // This handles the case where the redirect URI has #access_token=...
                        tryExtractTokenFromUrl(currentUrl)
                    },
                    onPageFinished = { url, webView ->
                        // For OOB flow, itch.io shows the token on a page.
                        // Try to extract it from the page body via JavaScript.
                        tryExtractTokenFromUrl(url)

                        // Only try extracting from page content if we're on the redirect URI page
                        if (url.contains("urn:ietf:wg:oauth:2.0:oob") || url.contains("/authorize/") || url.contains("?code=") || url.contains("#access_token=")) {
                            webView.evaluateJavascript(
                            """(function(){
                                try {
                                    // itch.io OOB pages may display the token in a code element
                                    var codeEl = document.querySelector('code');
                                    if (codeEl && codeEl.innerText) return codeEl.innerText.trim();
                                    // Or in a pre element
                                    var preEl = document.querySelector('pre');
                                    if (preEl && preEl.innerText) return preEl.innerText.trim();
                                    // Or check for the token in the URL hash
                                    if (window.location.hash) {
                                        var params = new URLSearchParams(window.location.hash.substring(1));
                                        var token = params.get('access_token');
                                        if (token) return token;
                                    }
                                    return null;
                                } catch(e) { return null; }
                            })();"""
                        ) { result ->
                            val token = unquoteJsonString(result)
                            if (!token.isNullOrBlank() && token.length > 10) {
                                Timber.tag("Itch").d("Extracted access token from page body")
                                finishWithToken(token)
                            }
                        }
                        }
                    },
                )
            }
        }
    }

    /**
     * Try to extract the access_token from a URL's fragment.
     * Itch.io implicit flow redirects to:
     *   {redirect_uri}#access_token=TOKEN&state=STATE&token_type=bearer
     */
    private fun tryExtractTokenFromUrl(url: String) {
        if (hasFinished) return

        try {
            val uri = Uri.parse(url)
            val fragment = uri.fragment ?: return

            // Parse fragment as query parameters
            val params = fragment.split("&").associate { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }

            val accessToken = params["access_token"]
            if (!accessToken.isNullOrBlank()) {
                // Validate state for CSRF protection
                val returnedState = params["state"]
                if (returnedState != null && returnedState != oauthState) {
                    Timber.tag("Itch").w("OAuth callback state mismatch; ignoring (possible CSRF)")
                    return
                }

                Timber.tag("Itch").d("Extracted access token from URL fragment")
                finishWithToken(accessToken)
            }
        } catch (e: Exception) {
            Timber.tag("Itch").w(e, "Failed to extract token from URL")
        }
    }

    private fun finishWithToken(token: String) {
        if (hasFinished) return
        hasFinished = true

        val resultIntent = Intent().apply {
            putExtra(EXTRA_ACCESS_TOKEN, token)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    /** evaluateJavascript returns a JSON-encoded string. Strip quotes and unescape. */
    private fun unquoteJsonString(jsResult: String?): String? {
        if (jsResult.isNullOrBlank()) return null
        val raw = jsResult.trim()
        if (raw == "null") return null
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) return raw
        return raw.drop(1).dropLast(1).replace("\\\"", "\"")
    }
}
