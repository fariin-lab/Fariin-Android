package com.fariin.app.media

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-flight media sends, held so a tap on the upload ring can CANCEL them (iOS MediaSend).
 * Cancel stops the send job (the storage upload is cancelled with it), and the chat removes the
 * bubble and takes down any message document the send had already written, so no bubble is left
 * spinning on either phone.
 */
object MediaSend {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val announced = ConcurrentHashMap<String, String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun register(clientId: String, job: Job) { jobs[clientId] = job }

    /** Is a send for this clientId running in this process right now? */
    fun isInFlight(clientId: String) = jobs.containsKey(clientId)

    /** Cancel the whole send. */
    fun cancel(clientId: String) {
        cancelled.add(clientId)
        jobs[clientId]?.cancel()
    }

    /** The send is writing its message document (before the bytes are up). */
    fun noteAnnounced(clientId: String?, messageId: String) { if (clientId != null) announced[clientId] = messageId }

    /** The document this send wrote, if any: what Cancel has to delete. */
    fun announcedId(clientId: String): String? = announced[clientId]

    /** True when the failure just caught was the person pressing Cancel. */
    fun wasCancelled(clientId: String) = clientId in cancelled

    // ---- one album item (iOS MediaSend.itemKey / cancelItem): the X on a tile drops that item and
    // the album goes without it; every other tile keeps its own key (its original index).

    private val itemJobs = ConcurrentHashMap<String, Job>()
    private val _cancelledItems = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val cancelledItems: kotlinx.coroutines.flow.StateFlow<Set<String>> = _cancelledItems

    fun itemKey(clientId: String, index: Int) = "$clientId#$index"

    fun registerItem(key: String, job: Job) { itemJobs[key] = job }
    fun itemEnded(key: String) { itemJobs.remove(key) }

    fun isItemCancelled(key: String) = key in _cancelledItems.value

    fun cancelItem(key: String) {
        _cancelledItems.value = _cancelledItems.value + key
        itemJobs.remove(key)?.cancel()
        UploadProgress.done(key)
    }

    /** The album was sent or dropped: its item marks go (a failed one keeps them for its retry). */
    fun clearItems(clientId: String) {
        _cancelledItems.value = _cancelledItems.value.filterNot { it.startsWith("$clientId#") }.toSet()
        itemJobs.keys.removeAll { it.startsWith("$clientId#") }
    }

    /** The send finished, failed or was cancelled: forget everything filed under its id. */
    fun finish(clientId: String) {
        jobs.remove(clientId)
        announced.remove(clientId)
        cancelled.remove(clientId)
    }
}
