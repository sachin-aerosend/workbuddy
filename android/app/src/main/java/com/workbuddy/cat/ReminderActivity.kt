package com.workbuddy.cat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * A small card over whatever you're doing. Two jobs:
 *  - "remind me to… in N minutes" (default)
 *  - "how long on <app>?" with a custom number of minutes (Focus mode, via focusPkg/focusLabel extras)
 */
class ReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focusPkg = intent.getStringExtra("focusPkg")
        val focusLabel = intent.getStringExtra("focusLabel") ?: focusPkg
        setContent {
            var what by remember { mutableStateOf("") }
            var mins by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { finish() }, contentAlignment = Alignment.Center) {
                InkCard(Modifier.padding(24.dp).fillMaxWidth().clickable(enabled = false) {}, fill = Ink.cream) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CatSprite("idle", 44.dp)
                            Text(if (focusPkg != null) "  how long on $focusLabel?" else "  remind me to…", style = H2)
                        }
                        if (focusPkg == null) Field(what, "finish the proposal", fieldModifier = Modifier.focusRequester(focus)) { what = it }
                        ChipRow {
                            listOf(5, 10, 15, 30, 60).forEach { m -> Chip(if (m >= 60) "1 h" else "$m min", false) { save(focusPkg, what, m) } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("or in  ", style = Small)
                            Field(mins, "20", Modifier.width(72.dp), numeric = true, fieldModifier = if (focusPkg != null) Modifier.focusRequester(focus) else Modifier) {
                                mins = it.filter { c -> c.isDigit() }.take(4)
                            }
                            Text("  min   ", style = Small)
                            PillButton(if (focusPkg != null) "start ⏱" else "set ⏰") { mins.toIntOrNull()?.takeIf { it in 1..1440 }?.let { save(focusPkg, what, it) } }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Field(value: String, hint: String, modifier: Modifier = Modifier, numeric: Boolean = false, fieldModifier: Modifier = Modifier, onChange: (String) -> Unit) {
        Box(modifier.background(Color.White, RoundedCornerShape(10.dp)).border(2.dp, Ink.ink, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = Body.copy(color = Ink.muted))
            BasicTextField(value, onChange, singleLine = true, textStyle = Body.copy(color = Ink.ink),
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = fieldModifier.fillMaxWidth())
        }
    }

    private fun save(focusPkg: String?, text: String, minutes: Int) {
        val cat = Bus.cat
        if (focusPkg != null) {
            cat?.startFocus(focusPkg, minutes)
        } else if (cat != null) cat.addReminder(text, minutes)
        else startForegroundService(Intent(this, CatService::class.java).setAction(CatService.ACTION_ADD_REMINDER).putExtra("text", text).putExtra("minutes", minutes))
        finish()
    }
}
