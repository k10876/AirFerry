package com.airferry.sender.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.airferry.sender.encode.PreparationTask
import com.airferry.sender.encode.PrepareTransfer
import com.airferry.sender.encode.SpeedPresets
import com.airferry.sender.encode.TransferParams
import com.airferry.sender.encode.TransferPlan
import com.airferry.sender.nativelib.NativeBridge
import com.airferry.sender.share.ShareIntake
import com.airferry.sender.share.StagedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val BgDark = Color(0xFF0F172A)
private val CardBg = Color(0xFF1E293B)
private val Accent = Color(0xFF3B82F6)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val Danger = Color(0xFFEF4444)

class ShareActivity : ComponentActivity() {

    private val preparation by lazy { PreparationTask(lifecycleScope) }
    private val items = mutableStateListOf<StagedItem>()
    private var errorMsg by mutableStateOf<String?>(null)
    private var encoding by mutableStateOf(false)
    private var encodingLabel by mutableStateOf("正在准备…")
    private var plan by mutableStateOf<TransferPlan?>(null)
    private var handle by mutableLongStateOf(0L)
    private var segmentIndex by mutableIntStateOf(0)
    private var params by mutableStateOf(TransferParams())
    private var playing by mutableStateOf(false)
    private var nativeError by mutableStateOf<String?>(null)

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        try {
            stopPlayback()
            items.clear()
            items += ShareIntake.copyUris(this, uris)
            errorMsg = null
            plan = null
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeError = checkNative()
        loadParams()
        // Consume temporary URI grants before composing the first screen.
        ingestIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                ShareRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestIntent(intent)
    }

    override fun onDestroy() {
        preparation.cancel()
        destroyHandle()
        super.onDestroy()
    }

    private fun checkNative(): String? {
        return try {
            val v = NativeBridge.nativeAbiVersion()
            if (v < NativeBridge.NATIVE_ABI_VERSION) {
                "原生库版本过旧（ABI $v < ${NativeBridge.NATIVE_ABI_VERSION}）"
            } else {
                null
            }
        } catch (e: UnsatisfiedLinkError) {
            "无法加载 transfer_engine 原生库: ${e.message}"
        } catch (e: Exception) {
            "原生库自检失败: ${e.message}"
        }
    }

    private fun ingestIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        try {
            stopPlayback()
            items.clear()
            items += ShareIntake.copyFromIntent(this, intent)
            errorMsg = null
            plan = null
        } catch (e: Exception) {
            errorMsg = e.message ?: e.toString()
        }
    }

    private fun loadParams() {
        val p = getSharedPreferences("sender", MODE_PRIVATE)
        val symbol = p.getInt("symbolSize", SpeedPresets.DEFAULT.symbolSize)
        val preset = SpeedPresets.forSymbolSize(symbol) ?: SpeedPresets.DEFAULT
        params = TransferParams(
            redundancyPct = p.getInt("redundancyPct", 5).coerceIn(5, 50),
            fps = p.getInt("fps", preset.fps),
            symbolSize = preset.symbolSize,
            multiQr = if (p.getBoolean("multiQr", true)) 4 else 1
        )
    }

    private fun saveParams() {
        getSharedPreferences("sender", MODE_PRIVATE).edit()
            .putInt("redundancyPct", params.redundancyPct)
            .putInt("fps", params.fps)
            .putInt("symbolSize", params.symbolSize)
            .putBoolean("multiQr", params.multiQr >= 2)
            .apply()
    }

    private fun destroyHandle() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            try {
                NativeBridge.senderDestroy(h)
            } catch (_: Exception) {
            }
        }
    }

    private fun stopPlayback() {
        preparation.cancel()
        encoding = false
        plan = null
        playing = false
        destroyHandle()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun startPlayback(p: TransferPlan, index: Int) {
        destroyHandle()
        val h = PrepareTransfer.createHandle(p, index, params)
        if (h == 0L) {
            errorMsg = "无法创建发送会话"
            playing = false
            return
        }
        handle = h
        segmentIndex = index
        plan = p
        playing = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 1f
        window.attributes = lp
    }

    private fun encodeAndPlay() {
        if (encoding) return
        val snapshot = items.toList()
        encoding = true
        encodingLabel = "正在压缩并编码…"
        errorMsg = null
        preparation.start(
            prepare = {
                withContext(Dispatchers.Default) { PrepareTransfer.run(snapshot) }
            },
            onReady = { built ->
                saveParams()
                startPlayback(built, 0)
            },
            onError = { e -> errorMsg = e.message ?: e.toString() },
            onFinished = { encoding = false }
        )
    }

    @Composable
    private fun ShareRoot() {
        val native = nativeError
        when {
            native != null -> MessagePane("无法启动发送端", native, Danger)
            playing && handle != 0L && plan != null -> PlayPane(plan!!)
            encoding -> EncodingPane()
            items.isNotEmpty() -> ReviewPane()
            else -> HomePane()
        }
    }

    @Composable
    private fun HomePane() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.QrCode2,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("AirFerry 发送", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "从其他 App 的分享菜单选「AirFerry 发送」，或在这里选择文件。对准另一台设备上的 AirFerry 扫码端播放二维码。",
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("选择文件")
            }
            errorMsg?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = Danger, textAlign = TextAlign.Center)
            }
        }
    }

    @Composable
    private fun EncodingPane() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(16.dp))
            Text(encodingLabel, color = TextPrimary)
        }
    }

    @Composable
    private fun MessagePane(title: String, body: String, bodyColor: Color) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(body, color = bodyColor, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun ReviewPane() {
        val total = items.sumOf { it.size }
        val selected = SpeedPresets.forSymbolSize(params.symbolSize) ?: SpeedPresets.DEFAULT
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("准备发送", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    items.take(8).forEach { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                (if (item.isText) "📝 " else "") + item.displayName,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(ShareIntake.formatBytes(item.size), color = TextSecondary)
                        }
                    }
                    if (items.size > 8) {
                        Text("…还有 ${items.size - 8} 项", color = TextSecondary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${items.size} 项 · 合计 ${ShareIntake.formatBytes(total)}" +
                            if (items.size >= 2) " · 将打包为 ETBUNDL1" else "",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("速度预设", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                SpeedPresets.ALL.forEach { preset ->
                    FilterChip(
                        selected = preset.id == selected.id,
                        onClick = {
                            params = params.copy(symbolSize = preset.symbolSize, fps = preset.fps)
                        },
                        label = { Text(preset.label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${selected.blurb} · ${params.fps} fps", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("四码并行", color = TextPrimary)
                    Text("同屏 4 个不同符号，吞吐约 4×", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = params.multiQr >= 2,
                    onCheckedChange = { on -> params = params.copy(multiQr = if (on) 4 else 1) }
                )
            }
            Text("冗余 ${params.redundancyPct}%", color = TextPrimary)
            Slider(
                value = params.redundancyPct.toFloat(),
                onValueChange = { params = params.copy(redundancyPct = ((it / 5f).toInt() * 5).coerceIn(5, 50)) },
                valueRange = 5f..50f,
                steps = 8
            )
            errorMsg?.let {
                Text(it, color = Danger, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { encodeAndPlay() },
                enabled = !encoding,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("开始发送")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    stopPlayback()
                    items.clear()
                    plan = null
                    errorMsg = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除")
            }
        }
    }

    @Composable
    private fun PlayPane(p: TransferPlan) {
        var statsText by androidx.compose.runtime.remember { mutableStateOf("启动中…") }
        LaunchedEffect(handle) {
            while (handle != 0L) {
                try {
                    val raw = NativeBridge.senderStatsJson(handle)
                    if (raw != null && raw.isNotEmpty()) {
                        statsText = formatStats(raw)
                    }
                } catch (_: Exception) {
                }
                delay(250)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgDark)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { stopPlayback() }) {
                    Icon(Icons.Filled.Close, contentDescription = "停止", tint = TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        p.displayName,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(statsText, color = TextSecondary, fontSize = 12.sp)
                }
            }
            if (p.segmented) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBg)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { startPlayback(p, (segmentIndex - 1).coerceAtLeast(0)) },
                        enabled = segmentIndex > 0
                    ) { Text("上一段") }
                    Text(
                        "第 ${segmentIndex + 1} / ${p.segments.size} 段",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = {
                            startPlayback(p, (segmentIndex + 1).coerceAtMost(p.segments.lastIndex))
                        },
                        enabled = segmentIndex < p.segments.lastIndex,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("下一段") }
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    QrPlayView(ctx).apply {
                        this.handle = handle
                        this.fps = params.fps
                        this.multiQr = params.multiQr
                        start()
                    }
                },
                update = { view ->
                    view.handle = handle
                    view.fps = params.fps
                    view.multiQr = params.multiQr
                    if (!view.running) view.start()
                }
            )
            Text(
                "将接收端摄像头对准屏幕，保持画面完整可见",
                color = Color(0xFF334155),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }
}

private fun formatStats(raw: ByteArray): String {
    val json = raw.toString(Charsets.UTF_8).trim('\u0000')
    fun num(key: String): Double {
        val needle = "\"$key\":"
        val i = json.indexOf(needle)
        if (i < 0) return 0.0
        val rest = json.substring(i + needle.length).trimStart()
        val end = rest.indexOfFirst { it == ',' || it == '}' }.let { if (it < 0) rest.length else it }
        return rest.substring(0, end).trim().toDoubleOrNull() ?: 0.0
    }
    val fps = num("fps")
    val kb = num("throughput_bps") / 1024.0
    val frames = num("frames").toLong()
    return "${fps.toInt()} 符号/秒  ·  ${"%.1f".format(kb)} KB/s  ·  $frames 帧"
}
