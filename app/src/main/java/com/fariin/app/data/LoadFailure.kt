package com.fariin.app.data

/**
 * The short reason drawn under "Couldn't load ..." when a fetch or a listener fails. Only two
 * answers on purpose: no connection, or something else. The raw server text never reaches the
 * screen.
 */
object LoadFailure {
    const val OFFLINE = "No connection. Check your internet and try again."
    const val OTHER = "Something went wrong. Try again in a moment."

    /** Firestore codes that mean the server could not be reached. */
    private val NETWORK_CODES = setOf("UNAVAILABLE", "DEADLINE_EXCEEDED")

    /** Pure, tested: [offline] is the phone's own view of the network, [code] a Firestore code name. */
    fun reason(offline: Boolean, code: String?): String =
        if (offline || code in NETWORK_CODES) OFFLINE else OTHER

    /** The reason for an error as it came from Firebase or the network stack. */
    fun of(e: Throwable?): String {
        val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name
        val net = e is java.io.IOException || e?.cause is java.io.IOException
        return reason(net || ConnectionStatus.state.value != ConnectionStatus.State.ONLINE, code)
    }
}
