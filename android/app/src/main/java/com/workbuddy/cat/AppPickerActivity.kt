package com.workbuddy.cat

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Pick an app for Focus mode, then choose how the cat treats it (whole app, or just its Reels/Shorts). */
class AppPickerActivity : ComponentActivity() {
    data class AppInfo(val pkg: String, val label: String, val icon: Bitmap)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val apps = loadApps()
        setContent {
            var q by remember { mutableStateOf("") }
            var choosing by remember { mutableStateOf<AppInfo?>(null) }
            Box(Modifier.fillMaxSize().background(Ink.cream)) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(18.dp, 14.dp, 18.dp, 8.dp)) {
                        Text("Add an app", style = H1)
                        Text("Pick something that eats your time.", style = Body)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(2.dp, Ink.ink, RoundedCornerShape(12.dp)).padding(12.dp, 9.dp)) {
                            if (q.isEmpty()) Text("Search apps…", style = Body.copy(color = Ink.muted))
                            BasicTextField(q, { q = it }, singleLine = true, textStyle = Body.copy(color = Ink.ink), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    LazyColumn {
                        items(apps.filter { it.label.contains(q, ignoreCase = true) }) { a ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { if (Feed.forPackage(a.pkg) != null) choosing = a else add(a, FocusMode.WHOLE) }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(a.icon.asImageBitmap(), null, Modifier.size(40.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(a.label, style = Strong)
                            }
                        }
                    }
                }
                choosing?.let { a ->
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { choosing = null }, contentAlignment = Alignment.Center) {
                        InkCard(Modifier.padding(24.dp).clickable(enabled = false) {}, fill = Ink.cream) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(a.label, style = H2)
                                Text("How should the cat handle it?", style = Body)
                                PillButton("Whole app — ask me how long") { add(a, FocusMode.WHOLE) }
                                PillButton("Just Reels / Shorts — swat the feed", primary = false) { add(a, FocusMode.FEED) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) null
                else AppInfo(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm).toBitmap(96, 96))
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun add(a: AppInfo, mode: FocusMode) {
        val p = Prefs.get(this)
        p.focusRules = p.focusRules.filter { it.pkg != a.pkg } + FocusRule(a.pkg, a.label, mode)
        finish()
    }
}
