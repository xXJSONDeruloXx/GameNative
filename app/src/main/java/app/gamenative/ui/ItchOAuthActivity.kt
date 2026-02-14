package app.gamenative.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gamenative.service.itch.ItchAuthManager
import app.gamenative.service.itch.ItchConstants
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * Activity to handle itch.io OAuth authorization code flow with PKCE.
 * 
 * PKCE (Proof Key for Code Exchange) flow:
 * 1. Generate code_verifier (random string)
 * 2. Generate code_challenge = base64url(sha256(code_verifier))
 * 3. Redirect user to itch.io OAuth with code_challenge
 * 4. User authorizes and itch.io redirects back with authorization code
 * 5. Exchange code + code_verifier for API key
 */
class ItchOAuthActivity : ComponentActivity() {
    
    private var codeVerifier: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start OAuth flow - OOB mode means user will see code on web page
        setContent {
            PluviaTheme {
                OAuthScreen()
            }
        }
    }
    
    @Composable
    fun OAuthScreen() {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var showCodeInput by remember { mutableStateOf(false) }
        var authCode by remember { mutableStateOf("") }
        var isExchanging by remember { mutableStateOf(false) }
        
        LaunchedEffect(Unit) {
            scope.launch {
                try {
                    status = "Opening browser..."
                    startOAuthFlow()
                    // Show code input after launching browser
                    kotlinx.coroutines.delay(1000)
                    showCodeInput = true
                    status = "Enter the authorization code from your browser"
                } catch (e: Exception) {
                    error = e.message
                    Timber.tag("Itch").e(e, "OAuth flow failed")
                }
            }
        }
        
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
                when {
                    error != null -> {
                        Text(
                            text = "Authentication Failed",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = error ?: "Unknown error")
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { finish() }) {
                            Text("Close")
                        }
                    }
                    showCodeInput -> {
                        Text(
                            text = "Enter Authorization Code",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Copy the authorization code from your browser and paste it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedTextField(
                            value = authCode,
                            onValueChange = { authCode = it },
                            label = { Text("Authorization Code") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isExchanging,
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { finish() },
                                enabled = !isExchanging,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    if (authCode.isNotBlank()) {
                                        scope.launch {
                                            isExchanging = true
                                            try {
                                                val result = ItchAuthManager.exchangeOAuthCode(
                                                    context = this@ItchOAuthActivity,
                                                    code = authCode.trim(),
                                                    codeVerifier = codeVerifier ?: "",
                                                    redirectUri = "urn:ietf:wg:oauth:2.0:oob"
                                                )
                                                
                                                if (result.isSuccess) {
                                                    Timber.tag("Itch").i("OAuth authentication successful")
                                                    setResult(RESULT_OK)
                                                    finish()
                                                } else {
                                                    error = result.exceptionOrNull()?.message
                                                    Timber.tag("Itch").e("OAuth code exchange failed: $error")
                                                }
                                            } catch (e: Exception) {
                                                error = e.message
                                                Timber.tag("Itch").e(e, "OAuth code exchange exception")
                                            } finally {
                                                isExchanging = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isExchanging && authCode.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isExchanging) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("Continue")
                                }
                            }
                        }
                    }
                    else -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = status)
                    }
                }
            }
        }
    }
    
    private fun startOAuthFlow() {
        // Generate PKCE parameters
        codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier!!)
        
        // Build OAuth authorization URL
        // Use itch.io's OOB (out-of-band) redirect which doesn't require app registration
        val redirectUri = "urn:ietf:wg:oauth:2.0:oob"
        val clientId = ItchConstants.OAUTH_CLIENT_ID
        
        val authUrl = Uri.parse("${ItchConstants.ITCH_BASE_URL}/user/oauth")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("scope", "profile:me")
            .build()
        
        Timber.tag("Itch").d("Starting OAuth flow: $authUrl")
        
        // Open browser for user authorization
        val intent = Intent(Intent.ACTION_VIEW, authUrl)
        startActivity(intent)
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
