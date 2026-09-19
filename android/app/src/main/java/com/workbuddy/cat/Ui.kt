package com.workbuddy.cat

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis

/** Shared look for the app screens: same palette and fonts as the website. */
object Ink {
    val cream = Color(0xFFFFFAF2); val paper = Color(0xFFFFF4E6); val fur = Color(0xFFFDD5B5)
    val ink = Color(0xFF2A2320); val ink2 = Color(0xFF4A3F3A); val muted = Color(0xFF7D6B62)
    val stripe = Color(0xFFD78C77); val mint = Color(0xFFD8F0DD); val sky = Color(0xFFD7EBFF); val blush = Color(0xFFFFE0DA)
    // Both fonts are variable fonts: pick the weight on the 'wght' axis explicitly.
    val pixel = FontFamily(Font(R.font.pixelify_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))))
    val body = FontFamily(
        Font(R.font.nunito, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    )
}

val H1 = TextStyle(fontFamily = Ink.pixel, fontSize = 30.sp, color = Ink.ink)
val H2 = TextStyle(fontFamily = Ink.pixel, fontSize = 20.sp, color = Ink.ink)
val Body = TextStyle(fontFamily = Ink.body, fontSize = 15.sp, color = Ink.ink2, lineHeight = 21.sp)
val Small = TextStyle(fontFamily = Ink.body, fontSize = 13.sp, color = Ink.muted, lineHeight = 18.sp)
val Strong = TextStyle(fontFamily = Ink.body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink.ink)

/** Card with an ink outline and a chunky offset shadow, like the website. */
@Composable
fun InkCard(modifier: Modifier = Modifier, fill: Color = Color.White, shadow: Dp = 4.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .drawBehind {
                val r = CornerRadius(16.dp.toPx())
                drawRoundRect(Ink.ink, topLeft = Offset(shadow.toPx(), shadow.toPx()), size = size, cornerRadius = r)
            }
            .background(fill, RoundedCornerShape(16.dp))
            .border(2.5.dp, Ink.ink, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
fun PillButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .drawBehind { drawRoundRect(Ink.ink, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = size, cornerRadius = CornerRadius(12.dp.toPx())) }
            .background(if (primary) Ink.fur else Color.White, RoundedCornerShape(12.dp))
            .border(2.5.dp, Ink.ink, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(text, style = Strong.copy(fontSize = 14.sp)) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Ink.fur else Color.White, RoundedCornerShape(999.dp))
            .border(2.dp, Ink.ink, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, style = Strong.copy(fontSize = 13.sp)) }
}

@Composable
fun ToggleRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Strong.copy(color = if (enabled) Ink.ink else Ink.muted))
            if (sub != null) Text(sub, style = Small)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, enabled = enabled, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Ink.stripe, checkedBorderColor = Ink.ink,
                uncheckedThumbColor = Ink.ink, uncheckedTrackColor = Ink.paper, uncheckedBorderColor = Ink.ink,
            ),
        )
    }
}

/** Chips that wrap onto the next line when they don't fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) { content() }
}

/** The animated pixel cat for app screens. */
@Composable
fun CatSprite(anim: String, sizeDp: Dp) {
    val sp = Sprites.get(LocalContext.current)
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(anim) { while (true) withFrameMillis { now = it } }
    val a = sp[anim]
    val paint = remember { Paint().apply { isFilterBitmap = false } }
    Canvas(Modifier.size(sizeDp)) {
        val f = if (a.fps == 0) 0 else ((now / 1000f * a.fps).toInt() % a.frames)
        val src = android.graphics.Rect(f * sp.frame, 0, (f + 1) * sp.frame, sp.frame)
        val dst = android.graphics.RectF(0f, 0f, size.width, size.height)
        drawContext.canvas.nativeCanvas.drawBitmap(a.sheet, src, dst, paint)
        SystemClock.uptimeMillis()
    }
}
