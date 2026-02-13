package app.gamenative.service.itch

import android.content.Context
import app.gamenative.data.ItchGame
import app.gamenative.db.dao.ItchGameDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Unified manager for itch.io game and library operations.
 *
 * Responsibilities:
 * - Database CRUD for itch.io games
 * - Library syncing from itch.io API
 *
 * Uses ItchApiClient for all API calls.
 * Uses ItchAuthManager for authentication checks.
 */
@Singleton
class ItchManager @Inject constructor(
    private val itchGameDao: ItchGameDao,
    @ApplicationContext private val context: Context,
) {

    private val REFRESH_BATCH_SIZE = 10

    suspend fun getGameFromDbById(gameId: Int): ItchGame? {
        return withContext(Dispatchers.IO) {
            try {
                itchGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "Failed to get itch.io game by ID: $gameId")
                null
            }
        }
    }

    suspend fun insertGame(game: ItchGame) {
        withContext(Dispatchers.IO) {
            itchGameDao.insert(game)
        }
    }

    suspend fun updateGame(game: ItchGame) {
        withContext(Dispatchers.IO) {
            itchGameDao.update(game)
        }
    }

    suspend fun deleteAllGames() {
        withContext(Dispatchers.IO) {
            itchGameDao.deleteAll()
        }
    }

    suspend fun getAllGameIds(): Set<Int> {
        return withContext(Dispatchers.IO) {
            try {
                itchGameDao.getAllGameIds().toSet()
            } catch (e: Exception) {
                Timber.tag("Itch").e(e, "Failed to get all game IDs")
                emptySet()
            }
        }
    }

    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ItchAuthManager.hasStoredCredentials(context)) {
                Timber.tag("Itch").w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("Itch").i("Starting itch.io library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("Itch").i("Background sync completed: $count games synced")
                return@withContext Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.tag("Itch").e(error, "Background sync failed: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "Failed to sync itch.io library in background")
            Result.failure(e)
        }
    }

    /**
     * Refresh the entire itch.io library.
     * Fetches all owned games from itch.io API and upserts into the database,
     * preserving installation status of existing games.
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!ItchAuthManager.hasStoredCredentials(context)) {
                Timber.tag("Itch").w("Cannot refresh library: not authenticated with itch.io")
                return@withContext Result.failure(Exception("Not authenticated with itch.io"))
            }

            Timber.tag("Itch").i("Refreshing itch.io library from API...")

            val result = ItchApiClient.getOwnedGames(context)

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Timber.tag("Itch").e(error, "Failed to fetch owned games: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch owned games"))
            }

            val games = result.getOrNull() ?: emptyList()
            Timber.tag("Itch").i("Successfully fetched ${games.size} games from itch.io")

            if (games.isEmpty()) {
                Timber.tag("Itch").w("No games found in itch.io library")
                return@withContext Result.success(0)
            }

            // Upsert in batches, preserving install status
            var totalProcessed = 0
            val batch = mutableListOf<ItchGame>()

            for ((index, game) in games.withIndex()) {
                batch.add(game)
                totalProcessed++

                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == games.size - 1) {
                    if (batch.isNotEmpty()) {
                        itchGameDao.upsertPreservingInstallStatus(batch)
                        Timber.tag("Itch").d("Batch upserted ${batch.size} games (processed ${index + 1}/${games.size})")
                        batch.clear()
                    }
                }
            }

            Timber.tag("Itch").i("Successfully refreshed itch.io library with $totalProcessed games")
            return@withContext Result.success(totalProcessed)
        } catch (e: Exception) {
            Timber.tag("Itch").e(e, "Failed to refresh itch.io library")
            return@withContext Result.failure(e)
        }
    }
}
