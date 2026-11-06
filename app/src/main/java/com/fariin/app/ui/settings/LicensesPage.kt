package com.fariin.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.theme.Fariin
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Help & About > Open-source licences. The free libraries inside the app and their
 * licences, collected at build time (AboutLibraries plugin), so the list is always complete. Apache-2.0,
 * MIT and the like ask an app that ships their code to carry their notice: this is that page. A tap
 * shows the licence text.
 */
@Composable
internal fun LicensesPage(pop: () -> Unit) {
    BackHandler(onBack = pop)
    val ctx = LocalContext.current
    val c = Fariin.colors
    val libs by produceState<List<Library>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val json = ctx.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
                Libs.Builder().withJson(json).build().libraries.distinctBy { it.name }.sortedBy { it.name.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    var open by remember { mutableStateOf<Library?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageBar("Open-source licences", onBack = pop)
        val list = libs
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.fariin.app.ui.components.FariinSpinner() }
        } else {
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = com.fariin.app.ui.shell.bottomBarInset() + 16.dp)) {
                item {
                    Text("Fariin is built with the help of these free and open-source libraries. Thank you to their authors.",
                        fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
                }
                items(list, key = { it.uniqueId }) { lib ->
                    Column(Modifier.fillMaxWidth().clickable { open = lib }.padding(horizontal = 32.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(lib.name, fontSize = 16.sp, color = c.label)
                        val who = lib.developers.mapNotNull { it.name }.firstOrNull() ?: lib.organization?.name
                        val lic = lib.licenses.joinToString { it.name }
                        Text(listOfNotNull(who, lic.ifEmpty { null }).joinToString(" · "), fontSize = 13.sp, color = c.secondary)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 32.dp))
                }
            }
        }
    }
    open?.let { lib ->
        val text = lib.licenses.mapNotNull { it.licenseContent }.firstOrNull()
            ?: lib.licenses.joinToString("\n") { it.name + (it.url?.let { u -> "\n$u" } ?: "") }
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
            title = { Text(lib.name, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(text, fontSize = 12.sp, color = c.secondary,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            },
        )
    }
}
