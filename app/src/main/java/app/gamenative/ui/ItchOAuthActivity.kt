package app.gamenative.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.gamenative.service.itch.ItchAuthManager
import app.gamenative.service.itch.ItchConstants
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Canonical itch.io OAuth activity.
 *
 * Flow:
 * 1. Generate state + PKCE verifier/challenge.
 * 2. Open browser to itch `/user/oauth` with `response_type=code`.
 * 3. Receive callback on `gamenative://itch/callback`.
 * 4. Validate state and exchange code + verifier at `/oauth/token`.
 */
class ItchOAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ERROR = "error"

        private const val STATE_OAUTH_STATE = "oauth_state"
        private const val STATE_CODE_VERIFIER = "code_verifier"
        private const val STATE_FLOW_STARTED = "flow_started"
        private const val STATE_STATUS_MESSAGE = "status_message"
        private const val STATE_ERROR_MESSAGE = "error_message"
    }

    private var oauthState: String? = null
    private var codeVerifier: String? = null
    private var flowStarted: Boolean = false
    private var flowFinished: Boolean = false

    private var statusMessage by mutableStateOf("Preparing sign-in...")
    private var errorMessage by mutableStateOf<String?>(null)
    private var isExchanging by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        if (savedInstanceState != null) {
            oauthState = savedInstanceState.getString(STATE_OAUTH_STATE)
            codeVerifier = savedInstanceState.getString(STATE_CODE_VERIFIER)
            flowStarted = savedInstanceState.getBoolean(STATE_FLOW_STARTED, false)
            statusMessage = savedInstanceState.getString(STATE_STATUS_MESSAGE) ?: statusMessage
            errorMessage = savedInstanceState.getString(STATE_ERROR_MESSAGE)
        }

        setContent {
            PluviaTheme {
                OAuthScreen()
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OAUTH_STATE, oauthState)
        outState.putString(STATE_CODE_VERIFIER, codeVerifier)
        outState.putBoolean(STATE_FLOW_STARTED, flowStarted)
        outState.putString(STATE_STATUS_MESSAGE, statusMessage)
        outState.putString(STATE_ERROR_MESSAGE, errorMessage)
    }

    private fun handleIntent(intent: Intent?) {
        if (flowFinished || isExchanging) return

        val data = intent?.data
        if (isOAuthCallback(data)) {
            handleOAuthCallback(data!!)
            return
        }

        if (!flowStarted) {
            startOAuthFlow()
        }
    }

    @Composable
    private fun OAuthScreen() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "itch.io Sign In",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (errorMessage == null) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { finishWithCancel() },
                        enabled = !isExchanging
                    ) {
                        Text("Cancel")
                    }
                } else {
                    Text(
                        text = "Authentication failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { finishWithCancel(errorMessage) },
                            enabled = !isExchanging
                        ) {
                            Text("Close")
                        }
                        Button(
                            onClick = { restartOAuthFlow() },
                            enabled = !isExchanging
                        ) {
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }

    private fun finishWithCancel(message: String? = null) {
        if (flowFinished) {
            finish()
            return
        }

        if (message != null) {
            val resultIntent = Intent().apply { putExtra(EXTRA_ERROR, message) }
            setResult(RESULT_CANCELED, resultIntent)
        } else {
            setResult(RESULT_CANCELED)
        }

        flowFinished = true
        finish()
    }

    private fun finishWithSuccess() {
        if (flowFinished) return
        setResult(RESULT_OK)
        flowFinished = true
        finish()
    }

    private fun restartOAuthFlow() {
        if (isExchanging) return

        oauthState = null
        codeVerifier = null
        flowStarted = false
        errorMessage = null
        statusMessage = "Preparing sign-in..."

        startOAuthFlow()
    }

    private fun isOAuthCallback(uri: Uri?): Boolean {
        if (uri == null) return false

        return uri.scheme == "gamenative" &&
            uri.host == "itch" &&
            (uri.path ?: "").startsWith("/callback")
    }

    private fun handleOAuthCallback(callbackUri: Uri) {
        val error = callbackUri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val errorDescription = callbackUri.getQueryParameter("error_description")
            val message = errorDescription ?: "OAuth error: $error"
            Timber.tag("Itch").e("OAuth callback error: $message")
            errorMessage = message
            statusMessage = "Authentication failed."
            return
        }

        val code = callbackUri.getQueryParameter("code")
        val state = callbackUri.getQueryParameter("state")
        val expectedState = oauthState
        val verifier = codeVerifier

        if (code.isNullOrBlank()) {
            errorMessage = "OAuth callback missing authorization code."
            statusMessage = "Authentication failed."
            return
        }

        if (expectedState.isNullOrBlank() || state != expectedState) {
            errorMessage = "OAuth state validation failed."
            statusMessage = "Authentication failed."
            return
        }

        if (verifier.isNullOrBlank()) {
            errorMessage = "OAuth PKCE verifier missing."
            statusMessage = "Authentication failed."
            return
        }

        exchangeAuthorizationCode(code, verifier)
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String) {
        if (isExchanging) return

        isExchanging = true
        errorMessage = null
        statusMessage = "Completing sign-in..."

        lifecycleScope.launch {
            try {
                val result = ItchAuthManager.exchangeOAuthCode(
                    context = this@ItchOAuthActivity,
                    code = code,
                    codeVerifier = verifier,
                    redirectUri = ItchConstants.ITCH_REDIRECT_URI
                )

                if (result.isSuccess) {
                    Timber.tag("Itch").i("OAuth authentication successful")
                    finishWithSuccess()
                } else {
                    val message = result.exceptionOrNull()?.message ?: "OAuth code exchange failed."
                    Timber.tag("Itch").e("OAuth code exchange failed: $message")
                    errorMessage = message
                    statusMessage = "Authentication failed."
                }
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "OAuth exchange exception")
                errorMessage = e.message ?: "OAuth exchange failed."
                statusMessage = "Authentication failed."
            } finally {
                isExchanging = false
            }
        }
    }

    private fun startOAuthFlow() {
        if (isExchanging) return

        codeVerifier = generateCodeVerifier()
        oauthState = ItchConstants.generateOAuthState()
        flowStarted = true
        errorMessage = null

        val authUrl = ItchConstants.buildAuthorizationCodeUrl(
            state = oauthState!!,
            codeChallenge = generateCodeChallenge(codeVerifier!!)
        )

        try {
            statusMessage = "Opening browser for authorization..."
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            statusMessage = "Complete authorization in your browser."
            Timber.tag("Itch").d("Started OAuth browser flow")
        } catch (e: ActivityNotFoundException) {
            Timber.tag("Itch").e(e, "No browser available for OAuth")
            errorMessage = "No browser available to complete OAuth."
            statusMessage = "Authentication failed."
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "Failed to launch browser for OAuth")
            errorMessage = e.message ?: "Failed to start OAuth flow."
            statusMessage = "Authentication failed."
        }
    }

    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray())
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
