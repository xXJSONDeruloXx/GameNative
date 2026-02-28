package app.gamenative.utils

import android.content.Context
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import timber.log.Timber

object PlatformOAuthHandlers {

    private suspend fun <T> handleAuthentication(
        platformName: String,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onDialogClose: () -> Unit,
        authenticate: suspend () -> Result<T>,
        startService: () -> Unit,
        triggerSync: () -> Unit,
        onSuccess: () -> Unit,
    ) {
        onLoadingChange(true)
        onError(null)

        try {
            Timber.d("[PlatformOAuth]: Starting $platformName authentication...")
            val result = authenticate()

            if (result.isSuccess) {
                Timber.i("[PlatformOAuth]: $platformName authentication successful!")
                startService()
                triggerSync()
                onSuccess()
                onLoadingChange(false)
                onDialogClose()
            } else {
                val error = result.exceptionOrNull()?.message ?: "Authentication failed"
                Timber.e("[PlatformOAuth]: $platformName authentication failed: $error")
                onLoadingChange(false)
                onError(error)
            }
        } catch (e: Exception) {
            Timber.e(e, "[PlatformOAuth]: $platformName authentication exception: ${e.message}")
            onLoadingChange(false)
            onError(e.message ?: "Authentication failed")
        }
    }

    suspend fun handleGogAuthentication(
        context: Context,
        authCode: String,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onSuccess: (Int) -> Unit,
        onDialogClose: () -> Unit,
    ) {
        handleAuthentication(
            platformName = "GOG",
            onLoadingChange = onLoadingChange,
            onError = onError,
            onDialogClose = onDialogClose,
            authenticate = { GOGService.authenticateWithCode(context, authCode) },
            startService = { GOGService.start(context) },
            triggerSync = { GOGService.triggerLibrarySync(context) },
            onSuccess = { onSuccess(0) },
        )
    }

    suspend fun handleEpicAuthentication(
        context: Context,
        authCode: String,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onSuccess: () -> Unit,
        onDialogClose: () -> Unit,
    ) {
        handleAuthentication(
            platformName = "Epic",
            onLoadingChange = onLoadingChange,
            onError = onError,
            onDialogClose = onDialogClose,
            authenticate = { EpicService.authenticateWithCode(context, authCode) },
            startService = { EpicService.start(context) },
            triggerSync = { EpicService.triggerLibrarySync(context) },
            onSuccess = onSuccess,
        )
    }

    suspend fun handleAmazonAuthentication(
        context: Context,
        authCode: String,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
        onSuccess: () -> Unit,
        onDialogClose: () -> Unit,
    ) {
        handleAuthentication(
            platformName = "Amazon",
            onLoadingChange = onLoadingChange,
            onError = onError,
            onDialogClose = onDialogClose,
            authenticate = { AmazonService.authenticateWithCode(context, authCode) },
            startService = { AmazonService.start(context) },
            triggerSync = { AmazonService.triggerLibrarySync(context) },
            onSuccess = onSuccess,
        )
    }
}
