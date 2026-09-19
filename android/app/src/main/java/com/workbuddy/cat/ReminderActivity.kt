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

/** "Remind me to… in N minutes" – a small card over whatever you're doing. */
class ReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            Text("  remind me to…", style = H2)
                        }
                        Field(what, "finish the proposal", fieldModifier = Modifier.focusRequester(focus)) { what = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 15, 30, 60).forEach { m -> Chip(if (m >= 60) "1 h" else "$m min", false) { save(what, m) } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("or in  ", style = Small)
                            Field(mins, "20", Modifier.width(72.dp), numeric = true) { mins = it.filter { c -> c.isDigit() }.take(4) }
                            Text("  min   ", style = Small)
                            PillButton("set ⏰") { mins.toIntOrNull()?.takeIf { it in 1..1440 }?.let { save(what, it) } }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Field(value: String, hint: String, modifier: Modifier = Modifier, numeric: Boolean = false, fieldModifier: Modifier = Modifier, onChange: (String) -> Unit) {
        Box(modifier.background(Color.White, RoundedCornerShape(10.dp)).border(2.dp, Ink.ink, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = Body.copy(color = Ink.muted))
            BasicTextField(value, onChange, singleLine = true, textStyle = Body.copy(color = Ink.ink),
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = fieldModifier.fillMaxWidth())
        }
    }

    private fun save(text: String, minutes: Int) {
        val cat = Bus.cat
        if (cat != null) cat.addReminder(text, minutes)
        else startForegroundService(Intent(this, CatService::class.java).setAction(CatService.ACTION_ADD_REMINDER).putExtra("text", text).putExtra("minutes", minutes))
        finish()
    }
}
