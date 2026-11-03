package com.fariin.app.ui.people

import androidx.compose.runtime.staticCompositionLocalOf

/** Where the people pages open from anywhere under the shell (set by MainActivity's NavHost). */
data class PeopleNav(
    val newChat: () -> Unit = {},
    val requests: () -> Unit = {},
    val contact: (String) -> Unit = {},
    val group: (String) -> Unit = {},
    val newGroup: () -> Unit = {},
    val editProfile: () -> Unit = {},
    val newCall: () -> Unit = {},
    val newCallLink: () -> Unit = {},
    val callLink: (String) -> Unit = {},
    /** The join screen for a call link: its key (text form) and the type its path named. */
    val joinLink: (String, Boolean) -> Unit = { _, _ -> },
    /** A chat by its id (a contact card's Message button). */
    val chat: (String) -> Unit = {},
    /** The chat list's search reaching past the list (iOS lookUpPeople): who owns a username, and
     *  the chat with a person found that way (opening it is what creates it). */
    val searchPeople: suspend (String) -> List<com.fariin.app.data.Person> = { emptyList() },
    val startChat: suspend (com.fariin.app.data.Person) -> String? = { null },
)

val LocalPeopleNav = staticCompositionLocalOf { PeopleNav() }
