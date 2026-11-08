package io.github.jqssun.airplay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.audio.LightTuning
import java.util.Locale
import kotlin.math.roundToInt

/** A bounded overlay; the album/transport layout never reserves space for it. */
@Composable
internal fun LightTuningOverlay(
    tuning: LightTuning, raw: () -> Float, open: Boolean, onOpen: (Boolean) -> Unit,
    onChange: (LightTuning) -> Unit, onSave: () -> Unit, onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(open) { onSave(); onOpen(false) }
    if (!open) {
        FilledTonalButton(onClick = { onOpen(true) }, modifier = modifier) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("光效调节")
        }
        return
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = Color(0xF5232834), contentColor = Color.White, shadowElevation = 12.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("音乐光效 · 实时调节", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSave(); onOpen(false) }) {
                    Icon(Icons.Default.Close, contentDescription = "收起光效调节")
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) { LightMeters(raw, tuning) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)) {
                Text("数值即时生效并自动保存；100% 扩张范围可覆盖全屏。",
                    fontSize = 11.sp, color = Color(0xFFCBD3E4))
                LightSlider("检测灵敏度", tuning.sensitivity, 50..400, "%", onSave) {
                    onChange(tuning.copy(sensitivity = it))
                }
                LightSlider("响应增益", tuning.gain, 50..800, "%", onSave) {
                    onChange(tuning.copy(gain = it))
                }
                LightSlider("重拍分界", tuning.threshold, 0..90, "%", onSave) {
                    onChange(tuning.copy(threshold = it))
                }
                LightSlider("响应曲线", tuning.curve, 50..250, "%", onSave) {
                    onChange(tuning.copy(curve = it))
                }
                Text("曲线低于100%提升弱拍，高于100%突出重拍。", fontSize = 11.sp, color = Color(0xFFCBD3E4))
                LightSlider("最大扩张范围", tuning.extent, 0..200, "%", onSave) {
                    onChange(tuning.copy(extent = it))
                }
                LightSlider("柔和回落时间", tuning.release, 80..600, "ms", onSave) {
                    onChange(tuning.copy(release = it))
                }
                LightSlider("中频参与度", tuning.mid, 0..100, "%", onSave) {
                    onChange(tuning.copy(mid = it))
                }
                LightSlider("高频参与度", tuning.high, 0..100, "%", onSave) {
                    onChange(tuning.copy(high = it))
                }
                LightSlider("触发最短间隔", tuning.interval, 60..300, "ms", onSave) {
                    onChange(tuning.copy(interval = it))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("音乐光效参数", tuning.summary()))
                        Toast.makeText(context, "已复制当前参数", Toast.LENGTH_SHORT).show()
                    }) { Text("复制参数", color = Color(0xFFC3D6FF)) }
                    TextButton(onClick = onReset) { Text("恢复默认", color = Color(0xFFC3D6FF)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LightSlider(label: String, value: Int, range: IntRange, unit: String,
    onFinish: () -> Unit, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("$value$unit", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFAAC8FF))
    }
    Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = onFinish,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = SliderDefaults.colors(thumbColor = Color(0xFFAAC8FF), activeTrackColor = Color(0xFF8CB4FF),
            inactiveTrackColor = Color(0xFF505B70)))
}

@Composable
private fun LightMeters(raw: () -> Float, tuning: LightTuning) {
    // Only this small subtree observes per-frame PCM values.
    val detected = raw()
    val mapped = tuning.impact(detected)
    for ((label, value) in listOf("检测强度" to detected, "映射后强度" to mapped, "扩张响应" to tuning.expansion(detected))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, "%.1f%%", value * 100), fontSize = 11.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(5.dp).padding(vertical = 1.dp)) {
            drawRect(Color(0xFF505B70))
            drawRect(Color(0xFFAAC8FF), size = Size(size.width * value.coerceIn(0f, 1f), size.height))
        }
        Spacer(Modifier.height(5.dp))
    }
}
