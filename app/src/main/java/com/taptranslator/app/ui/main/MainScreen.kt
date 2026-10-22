package com.taptranslator.app.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taptranslator.app.data.model.LanguageOption
import com.taptranslator.app.data.model.OverlayDisplayMode
import com.taptranslator.app.data.model.TranslationTone
import com.taptranslator.app.service.FloatingOverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var showPassword by remember { mutableStateOf(false) }

    // Screen Capture (MediaProjection) launcher
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_START
                putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            viewModel.refreshServiceState()
            Toast.makeText(context, "플로팅 번역 버튼이 활성화되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "화면 캡처 권한이 승인되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // Overlay Permission launcher
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "오버레이 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // Refresh service running state when screen is opened
    LaunchedEffect(Unit) {
        viewModel.refreshServiceState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tap Translator",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    val statusText = if (uiState.isServiceRunning) "작동 중" else "중지됨"
                    val statusColor = if (uiState.isServiceRunning) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    Surface(
                        color = statusColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = "● $statusText",
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Service Start / Stop Hero Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isServiceRunning)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (uiState.isServiceRunning)
                            "플로팅 번역 버튼이 켜져 있습니다"
                        else
                            "번역 버튼을 화면 구석에 띄워 사용하세요",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "원하는 화면에서 플로팅 버튼을 터치하면 Gemini Flash가 즉시 번역합니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            if (uiState.isServiceRunning) {
                                val stopIntent = Intent(context, FloatingOverlayService::class.java).apply {
                                    action = FloatingOverlayService.ACTION_STOP
                                }
                                context.startService(stopIntent)
                                viewModel.refreshServiceState()
                            } else {
                                if (uiState.apiKey.isBlank()) {
                                    Toast.makeText(context, "먼저 Gemini API 키를 입력해주세요.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                // Check overlay permission
                                if (!Settings.canDrawOverlays(context)) {
                                    Toast.makeText(context, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    overlayPermissionLauncher.launch(intent)
                                    return@Button
                                }
                                // Request MediaProjection Screen Capture
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isServiceRunning)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isServiceRunning) "번역기 중지 (Stop)" else "번역기 시작 (Start Floating)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // Gemini API Key Settings
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (!uiState.isApiKeyExpanded && uiState.apiKey.isNotBlank()) {
                        // Collapsed view when API key is successfully linked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gemini API 연동 완료",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "보안 저장소 연동됨 (••••••••${uiState.apiKey.takeLast(4)})",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.setApiKeyExpanded(true) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("키 변경", fontSize = 12.sp)
                            }
                        }
                    } else {
                        // Expanded view for entering or editing API key
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gemini API 키 설정", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            if (uiState.apiKey.isNotBlank()) {
                                TextButton(
                                    onClick = { viewModel.setApiKeyExpanded(false) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("접기", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = uiState.apiKey,
                            onValueChange = { viewModel.updateApiKey(it) },
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("AIzaSy...") },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Google AI Studio에서 무료 발급 가능",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Button(
                                onClick = { viewModel.testApiKey() },
                                enabled = !uiState.isTestingKey && uiState.apiKey.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (uiState.isTestingKey) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("연결 테스트", fontSize = 12.sp)
                                }
                            }
                        }

                        uiState.testResultMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            val isSuccess = uiState.testSuccess == true
                            Surface(
                                color = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    color = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Language Settings Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("번역 언어 설정", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Source Language Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            LanguageDropdown(
                                label = "화면 언어 (원본)",
                                selectedCode = uiState.sourceLang,
                                options = LanguageOption.SUPPORTED_SOURCE,
                                onSelect = { viewModel.updateSourceLang(it) }
                            )
                        }

                        IconButton(
                            onClick = { viewModel.swapLanguages() },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Swap Languages")
                        }

                        // Target Language Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            LanguageDropdown(
                                label = "번역할 언어 (대상)",
                                selectedCode = uiState.targetLang,
                                options = LanguageOption.SUPPORTED_TARGET,
                                onSelect = { viewModel.updateTargetLang(it) }
                            )
                        }
                    }
                }
            }

            // Model Selection Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI 모델 연동 (Gemini Flash)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // Auto-sync banner
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (uiState.isCheckingModel) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "최신 Flash 모델 확인 중...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "최신 Flash 모델 자동 감지 연동 활성화",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "연결된 모델: ${uiState.selectedModel}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    val presetModels = listOf(
                        "gemini-3.8-flash" to "Gemini 3.8 Flash (최신 Google 권장 / 초고속)",
                        "gemini-1.5-flash" to "Gemini 1.5 Flash (안정적 구버전 / 부하 대체용)",
                        "gemini-3.8-pro" to "Gemini 3.8 Pro (최고 퀄리티 / 문맥 이해)"
                    )

                    var isCustom by remember(uiState.selectedModel) {
                        mutableStateOf(presetModels.none { it.first == uiState.selectedModel })
                    }
                    var customModelInput by remember(uiState.selectedModel) {
                        mutableStateOf(if (isCustom) uiState.selectedModel else "")
                    }

                    presetModels.forEach { (modelId, desc) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    isCustom = false
                                    viewModel.updateSelectedModel(modelId)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = !isCustom && uiState.selectedModel == modelId,
                                onClick = {
                                    isCustom = false
                                    viewModel.updateSelectedModel(modelId)
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = desc, fontSize = 13.sp)
                        }
                    }

                    // Custom model ID option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isCustom = true }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = isCustom,
                            onClick = { isCustom = true }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "직접 모델명 입력 (Custom Model ID)", fontSize = 13.sp)
                    }

                    if (isCustom) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = {
                                customModelInput = it
                                if (it.isNotBlank()) {
                                    viewModel.updateSelectedModel(it.trim())
                                }
                            },
                            label = { Text("모델 ID 직접 입력") },
                            placeholder = { Text("예: gemini-3.8-flash, gemini-2.5-flash 등") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            // Translation Tone Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("번역 톤 / 스타일", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    TranslationTone.values().forEach { tone ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.updateTone(tone) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = uiState.translationTone == tone,
                                onClick = { viewModel.updateTone(tone) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = tone.title, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Display Mode Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("화면 표시 방식", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    OverlayDisplayMode.values().forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.updateDisplayMode(mode) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = uiState.displayMode == mode,
                                onClick = { viewModel.updateDisplayMode(mode) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = mode.title, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Real-time Debug Logs Card
            val logScrollState = rememberScrollState()
            LaunchedEffect(logs) {
                if (logs.isNotBlank()) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("실시간 디버그 로그", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    viewModel.clearLogs()
                                    Toast.makeText(context, "로그가 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "로그 비우기",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    val fullLogs = viewModel.getFullLogs()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("TapTranslatorLogs", fullLogs)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "전체 로그가 클립보드에 복사되었습니다!", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("로그 복사", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "버튼 클릭 시 캡처, OCR, API 요청 상태가 실시간으로 기록됩니다. 문제가 발생하면 '로그 복사'를 눌러 분석에 활용하세요.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(10.dp)
                            .verticalScroll(logScrollState)
                    ) {
                        Text(
                            text = if (logs.isBlank()) "아직 기록된 로그가 없습니다.\n플로팅 버튼을 누르면 실시간 로그가 여기에 표시됩니다." else logs,
                            color = if (logs.isBlank()) Color(0xFF888888) else Color(0xFF80CBC4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdown(
    label: String,
    selectedCode: String,
    options: List<LanguageOption>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedItem = options.firstOrNull { it.code == selectedCode } ?: options.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedItem.displayName.split(" ").first(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.displayName, fontSize = 13.sp) },
                    onClick = {
                        onSelect(opt.code)
                        expanded = false
                    }
                )
            }
        }
    }
}
