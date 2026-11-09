package com.fariin.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** True while a sheet with its own text field covers the page: the page behind ignores the keyboard. */
object SheetKeyboard {
    var covering by mutableStateOf(false)
}
