package app.gamenative.service.itch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.gamenative.data.ItchCredentials
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Itch.io Service — thin abstraction layer that delegates to managers.
 *
 * Architecture follows the same pattern as GOGService / EpicService:
 * - ItchApiClient: API layer for interacting with itch.io's APIs
 * - ItchAuthManager: Authentication and credential management (OAuth Implicit Flow)
 * - ItchManager: Game library and database operations
 * - ItchConstants: Shared constants for itch.io integration
 */
@AndroidEntryPoint
class ItchService : Service() {

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.ITCH_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.ITCH_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: ItchService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            if (isRunning) {
                Timber.tag("Itch").d("[ItchService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.tag("Itch").i("[ItchService] First-time start - starting service with initial sync")
                val intent = Intent(context, ItchService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: always start service, but check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, ItchService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.tag("Itch").i("[ItchService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.tag("Itch").d("[ItchService] Starting service without sync - throttled (${remainingMinutes}min remaining)")
            }
            context.startForegroundService(intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.tag("Itch").i("[ItchService] Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, ItchService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to ItchAuthManager
        // ==========================================================================

        /**
         * Authenticate using access token from OAuth implicit flow.
         */
        suspend fun authenticateWithToken(context: Context, accessToken: String): Result<ItchCredentials> {
            return ItchAuthManager.authenticateWithToken(context, accessToken)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return ItchAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<ItchCredentials> {
            return ItchAuthManager.getStoredCredentials(context)
        }

        fun clearStoredCredentials(context: Context): Boolean {
            return ItchAuthManager.clearStoredCredentials(context)
        }

        /**
         * Logout from itch.io - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.tag("Itch").i("[ItchService] Logging out from itch.io...")

                    // Clear stored credentials first, regardless of service state
                    val credentialsCleared = clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.tag("Itch").e("Failed to clear credentials during logout")
                        return@withContext Result.failure(Exception("Failed to clear stored credentials"))
                    }

                    // Get instance to clean up service-specific data
                    val instance = getInstance()
                    if (instance != null) {
                        // Clear all itch.io games from database
                        instance.itchManager.deleteAllGames()
                        Timber.tag("Itch").i("All itch.io games removed from database")

                        // Stop the service
                        stop()
                    } else {
                        Timber.tag("Itch").w("Service not running during logout, but credentials were cleared")
                    }

                    Timber.tag("Itch").i("Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Itch").e(e, "Error during logout")
                    Result.failure(e)
                }
            }
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): ItchService? = instance

        // ==========================================================================
        // LIBRARY OPERATIONS
        // ==========================================================================

        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.itchManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var itchManager: ItchManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.tag("Itch").i("[ItchService] Service created")

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("Itch").d("[ItchService] onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        val notification = notificationHelper.createForegroundNotification("Connected")
        startForeground(1, notification)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.tag("Itch").i("[ItchService] Manual sync requested - bypassing throttle")
                true
            }

            ACTION_SYNC_LIBRARY -> {
                Timber.tag("Itch").i("[ItchService] Automatic sync requested")
                true
            }

            null -> {
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                if (shouldResync) {
                    Timber.tag("Itch").i("[ItchService] Service restarted by Android - performing sync")
                    true
                } else {
                    Timber.tag("Itch").d("[ItchService] Service restarted by Android - skipping sync (throttled)")
                    false
                }
            }

            else -> {
                Timber.tag("Itch").d("[ItchService] Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.tag("Itch").i("[ItchService] Starting background library sync")
            backgroundSyncJob?.cancel()
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.tag("Itch").d("[ItchService] Starting background library sync")
                    val syncResult = itchManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.tag("Itch").w("[ItchService] Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.tag("Itch").i("[ItchService] Background library sync completed successfully")
                        lastSyncTimestamp = System.currentTimeMillis()
                        hasPerformedInitialSync = true
                    }
                } catch (e: Exception) {
                    Timber.tag("Itch").e(e, "[ItchService] Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.tag("Itch").d("[ItchService] Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Itch").i("[ItchService] Service destroyed")
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
