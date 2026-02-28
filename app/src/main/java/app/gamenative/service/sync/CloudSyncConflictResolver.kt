package app.gamenative.service.sync

data class CloudSyncConflictPlan<K>(
    val uploadKeys: Set<K>,
    val downloadKeys: Set<K>,
)

object CloudSyncConflictResolver {

    fun <K, L, R> buildPlan(
        localEntries: Map<K, L>,
        remoteEntries: Map<K, R>,
        localTimestamp: (L) -> Long,
        remoteTimestamp: (R) -> Long,
    ): CloudSyncConflictPlan<K> {
        val uploadKeys = linkedSetOf<K>()
        val downloadKeys = linkedSetOf<K>()

        val commonKeys = localEntries.keys.intersect(remoteEntries.keys)
        commonKeys.forEach { key ->
            val localTime = localTimestamp(localEntries.getValue(key))
            val remoteTime = remoteTimestamp(remoteEntries.getValue(key))
            when {
                localTime > remoteTime -> uploadKeys += key
                remoteTime > localTime -> downloadKeys += key
            }
        }

        uploadKeys += localEntries.keys - commonKeys
        downloadKeys += remoteEntries.keys - commonKeys

        return CloudSyncConflictPlan(
            uploadKeys = uploadKeys,
            downloadKeys = downloadKeys,
        )
    }
}
