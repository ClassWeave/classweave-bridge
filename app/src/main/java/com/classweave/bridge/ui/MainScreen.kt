package com.classweave.bridge.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classweave.bridge.R
import com.classweave.bridge.bluetooth.RfcommHub
import com.classweave.bridge.bluetooth.RfcommHubState
import com.classweave.bridge.core.BridgeSessionManager
import com.classweave.bridge.core.BridgeSessionState
import com.classweave.bridge.core.EndpointRegistry
import com.classweave.bridge.core.Forwarder
import com.classweave.bridge.model.AppLog
import com.classweave.bridge.protocol.BridgeIdentity
import com.classweave.bridge.usb.UsbConnectionState
import com.classweave.bridge.usb.UsbTransportClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _logs = MutableStateFlow<List<AppLog>>(emptyList())
    val logs: StateFlow<List<AppLog>> = _logs.asStateFlow()

    // --- Bridge (USB Transport + Session) ---
    private val _usbState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val usbState: StateFlow<UsbConnectionState> = _usbState.asStateFlow()

    private val _sessionState = MutableStateFlow(BridgeSessionState.DISCONNECTED)
    val sessionState: StateFlow<BridgeSessionState> = _sessionState.asStateFlow()

    private val _bridgeSessionId = MutableStateFlow<String?>(null)
    val bridgeSessionId: StateFlow<String?> = _bridgeSessionId.asStateFlow()

    private val _bridgeRunning = MutableStateFlow(false)
    val bridgeRunning: StateFlow<Boolean> = _bridgeRunning.asStateFlow()

    // --- Bluetooth RFCOMM ---
    private val _rfcommState = MutableStateFlow(RfcommHubState.IDLE)
    val rfcommState: StateFlow<RfcommHubState> = _rfcommState.asStateFlow()

    private val _requestDiscoverable = MutableStateFlow(false)
    val requestDiscoverable: StateFlow<Boolean> = _requestDiscoverable.asStateFlow()

    fun onDiscoverableHandled() { _requestDiscoverable.value = false }

    private val _androidDeviceCount = MutableStateFlow(0)
    val androidDeviceCount: StateFlow<Int> = _androidDeviceCount.asStateFlow()

    private val identity = BridgeIdentity()
    private val endpointRegistry = EndpointRegistry()
    private var usbTransport: UsbTransportClient? = null
    private var sessionManager: BridgeSessionManager? = null
    private var forwarder: Forwarder? = null
    private var rfcommHub: RfcommHub? = null
    private var bridgeScope: CoroutineScope? = null

    private fun addLog(log: AppLog) {
        _logs.update { it + log }
        Log.d("ClassWeaveBridge:${log.tag}", log.message)
    }

    // --- Bridge lifecycle ---

    fun startBridge() {
        if (_bridgeRunning.value) return

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        bridgeScope = scope

        val transport = UsbTransportClient(onLog = ::addLog)
        usbTransport = transport

        scope.launch { transport.connectionState.collect { _usbState.value = it } }

        val fwd = Forwarder(transport, endpointRegistry, identity, ::addLog)
        forwarder = fwd

        val hub = RfcommHub(getApplication(), onLog = ::addLog)
        rfcommHub = hub
        fwd.rfcommHub = hub

        val sm = BridgeSessionManager(transport, fwd, endpointRegistry, identity, ::addLog)
        sm.rfcommHub = hub
        sessionManager = sm

        scope.launch { sm.sessionState.collect { _sessionState.value = it } }
        scope.launch { sm.sessionId.collect { _bridgeSessionId.value = it } }
        scope.launch { hub.state.collect { _rfcommState.value = it } }
        scope.launch { endpointRegistry.devices.collect { _androidDeviceCount.value = it.size } }

        transport.start(scope)
        sm.start(scope)
        hub.startListening(scope)

        _bridgeRunning.value = true
        _requestDiscoverable.value = true
        addLog(AppLog(tag = "BRIDGE", message = "Bridge started"))
    }

    fun stopBridge() {
        rfcommHub?.stop()
        sessionManager?.stop()
        usbTransport?.stop()
        bridgeScope?.cancel()

        rfcommHub = null
        sessionManager = null
        forwarder = null
        usbTransport = null
        bridgeScope = null

        _bridgeRunning.value = false
        _usbState.value = UsbConnectionState.DISCONNECTED
        _sessionState.value = BridgeSessionState.DISCONNECTED
        _bridgeSessionId.value = null
        _rfcommState.value = RfcommHubState.IDLE
        _androidDeviceCount.value = 0

        addLog(AppLog(tag = "BRIDGE", message = "Bridge stopped"))
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        val allGranted = results.values.all { it }
        addLog(AppLog(
            tag = "PERMISSION",
            message = if (allGranted) "All permissions granted"
            else "Some permissions denied: ${results.filterValues { !it }.keys}"
        ))
    }

    override fun onCleared() {
        stopBridge()
        super.onCleared()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var showSplash by remember { mutableStateOf(true) }

    if (showSplash) {
        SplashScreen(onTimeout = { showSplash = false })
    } else {
        MainContent(viewModel = viewModel)
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1500)
        onTimeout()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo_red),
                contentDescription = "ClassWeave Logo",
                modifier = Modifier.width(160.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "ClassWeave Bridge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD32F2F)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "《移动互联网前沿技术》 MEM26Spring",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "陆文卿 毛祎玮 王嘉欣 张延洁",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.DarkGray
            )
        }
    }
}

@Composable
fun MainContent(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val usbState by viewModel.usbState.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val bridgeSessionId by viewModel.bridgeSessionId.collectAsState()
    val bridgeRunning by viewModel.bridgeRunning.collectAsState()
    val rfcommState by viewModel.rfcommState.collectAsState()
    val androidDeviceCount by viewModel.androidDeviceCount.collectAsState()
    val requestDiscoverable by viewModel.requestDiscoverable.collectAsState()

    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* user accepted or declined — either way, we continue */ }

    LaunchedEffect(requestDiscoverable) {
        if (requestDiscoverable) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            discoverableLauncher.launch(intent)
            viewModel.onDiscoverableHandled()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                // --- Left Column: Controls & Status (Fixed width) ---
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_logo_red),
                        contentDescription = "ClassWeave Logo",
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "ClassWeave Bridge",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // --- Bridge Controls ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.startBridge() },
                        enabled = !bridgeRunning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text("启动", fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopBridge() },
                        enabled = bridgeRunning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F))
                    ) {
                        Text("停止", fontSize = 14.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // --- USB + Session Status Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (sessionState) {
                            BridgeSessionState.ACTIVE -> Color(0xFFE8F5E9)
                            BridgeSessionState.HELLO_SENT -> Color(0xFFFFF3E0)
                            BridgeSessionState.DISCONNECTED ->
                                if (bridgeRunning) Color(0xFFFFEBEE) else Color(0xFFF5F5F5)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "USB 主机",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF424242)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(text = "连接: ${usbState.name}", fontSize = 13.sp)
                        Text(text = "会话: ${sessionState.name}", fontSize = 13.sp)
                        bridgeSessionId?.let { sid ->
                            Text(
                                text = "标识: $sid",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF757575),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // --- Bluetooth RFCOMM Status Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (rfcommState) {
                            RfcommHubState.LISTENING -> Color(0xFFE3F2FD)
                            RfcommHubState.ERROR -> Color(0xFFFFEBEE)
                            RfcommHubState.IDLE ->
                                if (bridgeRunning) Color(0xFFFFF3E0) else Color(0xFFF5F5F5)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "蓝牙设备连接",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF424242)
                        )
                        Spacer(Modifier.height(4.dp))
                        val stateText = when (rfcommState) {
                            RfcommHubState.LISTENING -> "等待连接"
                            RfcommHubState.ERROR -> "错误"
                            RfcommHubState.IDLE -> "未启动"
                        }
                        Text(text = "状态: $stateText", fontSize = 13.sp)
                        Text(text = "已连接 Android 设备数: $androidDeviceCount", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // --- Right Column: Logs (Takes remaining space) ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                Text(
                    text = "系统日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF424242)
                )

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
                ) {
                    LogList(
                        logs = logs,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                }
            }
            }

            // --- Bottom Fixed Team Info ---
            Text(
                text = "《移动互联网前沿技术》 MEM26Spring  |  陆文卿 毛祎玮 王嘉欣 张延洁",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}