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
)

val LocalPeopleNav = staticCompositionLocalOf { PeopleNav() }
