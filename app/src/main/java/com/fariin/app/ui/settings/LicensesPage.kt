package com.fariin.app.ui.settings

import com.fariin.app.ui.components.underBar

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
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
private val NL = 10.toChar().toString()

@Composable
internal fun LicensesPage(pop: () -> Unit) {
    BackHandler(onBack = pop)
    val ctx = LocalContext.current
    val c = Fariin.colors
    // One plain document, grouped by licence (the form big Android messengers use): which parts are
    // under each licence with their copyright line, then that licence's text once.
    val blocks by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val json = ctx.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
                val libs = Libs.Builder().withJson(json).build().libraries.distinctBy { it.name }
                val byLicence = libs.flatMap { l -> l.licenses.map { it to l } }
                    .groupBy({ it.first.name }, { it })
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                val out = ArrayList<String>()
                out += "This app contains software from third parties. Their attribution notices and licences follow."
                for ((name, pairs) in byLicence) {
                    val parts = pairs.map { it.second }.sortedBy { it.name.lowercase() }.joinToString(NL) { l ->
                        val holder = l.developers.mapNotNull { it.name }.firstOrNull() ?: l.organization?.name
                        "* " + l.name + (holder?.let { ", Copyright $it" } ?: "")
                    }
                    out += name + NL + NL + "The following components are licensed under " + name + " reproduced below:" + NL + NL + parts
                    pairs.firstNotNullOfOrNull { it.first.licenseContent }?.let { out += it.trim() }
                }
                out
            }.getOrDefault(emptyList())
        }
    }
    val barList = androidx.compose.foundation.lazy.rememberLazyListState()
    BarPage("Licences", onBack = pop, under = blocks != null && underBar(barList)) { top ->
        val list = blocks
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.fariin.app.ui.components.FariinSpinner() }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = barList, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(list.size) { i -> Text(list[i], fontSize = 13.sp, color = if (i == 0) c.label else c.secondary, lineHeight = 18.sp) }
            }
        }
    }
}

/**
 * Settings > Help & About > App info, the way big Android messengers keep it: the app's icon, name,
 * version and copyright, with the licences behind one small button at the bottom (owner 2026-11-06:
 * not a row of its own in Help). The licences must ship with the app; they need not stand out.
 */
@Composable
internal fun AppInfoPage(version: String, pop: () -> Unit) {
    BackHandler(onBack = pop)
    val ctx = LocalContext.current
    val c = Fariin.colors
    var licences by remember { mutableStateOf(false) }
    if (licences) { LicensesPage { licences = false }; return }
    val icon = remember {
        runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    BarPage("", onBack = pop) { top ->
        Column(Modifier.fillMaxSize().padding(top = top).padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("Fariin", fontSize = 20.sp, color = c.label)
            Text("Version $version", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
            if (icon != null) androidx.compose.foundation.Image(icon, contentDescription = null,
                modifier = Modifier.padding(vertical = 28.dp).size(88.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)))
            Text("© 2026 Fariin", fontSize = 14.sp, color = c.secondary)
            Box(Modifier.padding(top = 28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(c.blue).clickable { licences = true }.padding(horizontal = 28.dp, vertical = 10.dp)) {
                Text("Licences", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    }
}

