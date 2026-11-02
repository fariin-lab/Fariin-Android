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
)

val LocalPeopleNav = staticCompositionLocalOf { PeopleNav() }
