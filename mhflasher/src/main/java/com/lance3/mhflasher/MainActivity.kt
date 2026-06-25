package com.lance3.mhflasher

import ApViewModel
import LogViewModel
import WifiAP
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lance3.mhflasher.ui.theme.MagicHomeFlasherTheme
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

object Constants {
    const val OTA_PORT = 1111
    const val CMD_PORT = 48899
    const val CODE_PLACEHOLDER = "pierogi"
    const val TCP_OTA_FW_LIMIT = 0x55000
}

enum class CMD(val str: String){
    INFO("HF-A11ASSISTHREAD"),
    VER("AT+LVER\n"),
    OTA("AT+UPURL=http://10.10.123.4:${Constants.OTA_PORT}/update?version=33_00_dev_20260618_214208_OpenBeken&beta,${Constants.CODE_PLACEHOLDER}"),
    OTA_BETA("AT+UPURL=http://10.10.123.4:${Constants.OTA_PORT}/update?version=beta&beta,${Constants.CODE_PLACEHOLDER}"),
    OTA_BETA2("AT+UPURL=http://10.10.123.4:${Constants.OTA_PORT}/update?version=beta&x,${Constants.CODE_PLACEHOLDER}"),
    OTA_JSON("AT+UPURL={\"aes\":\"a50525456A\",\"url\":\"http://10.10.123.4:${Constants.OTA_PORT}/update\",\"ind\":0}\r\n")
}

enum class OtaTrigger {
    AT_UPURL,
    TCP_OTA
}

class MainActivity : ComponentActivity() {
    private val logViewModel = LogViewModel()
    private val apViewModel: ApViewModel by viewModels()
    companion object {
        const val MY_PERMISSIONS_REQUEST_LOCATION = 1
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LOG","On create")
        setContent {
            MagicHomeFlasherTheme {
                MyApp(logViewModel, apViewModel, {wifiCheckAndScan()}, {ap -> wifiConnect(ap)},{ip -> checkDeviceByIP(ip)}, {cmd -> prepareOtaAndFlash(applicationContext, OtaTrigger.AT_UPURL, cmd)}, {trigger -> prepareOtaAndFlash(applicationContext, trigger)}, {wifiReadConfig()}, {wifiSetAndReboot()})
            }
        }
        startHttpServer(applicationContext)
    }

    private fun scanSuccess(results: List<ScanResult>?, wifiscanReceiver: BroadcastReceiver) {
        unregisterReceiver(wifiscanReceiver)
        myLog("Success scan")
        apViewModel.clearAccessPoints()
        results?.forEach { scanResult ->
            myLog("AP found: ${scanResult.SSID}")
            apViewModel.addAccessPoint(
                WifiAP(
                    scanResult.SSID,
                    scanResult.level
                )
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_LOCATION -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    myLog("Permission granted")
                    wifiScan()
                } else {
                    myLog("Permission not granted")
                    Toast.makeText(
                        this,
                        "Unfortunately without this permission i was unable to scan WiFi (Android requirement)",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }
    }

    private fun wifiCheckAndScan(){
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            myLog("No permission yet")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                MY_PERMISSIONS_REQUEST_LOCATION
            )
        } else {
            myLog("Permission granted - skanujemy")
            wifiScan()
        }
    }
    private fun wifiScan() {

        myLog("Starting scan")
        apViewModel.clearAccessPoints()
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager?

        val wifiScanReceiver = object : BroadcastReceiver() {
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                    scanSuccess(wifiManager?.scanResults, this)
                } else {
                    Toast.makeText(
                        context,
                        "Unfortunately without this permission i was unable to scan WiFi (Android requirement)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        val success = wifiManager?.startScan()
        if (!success!!) {
            Toast.makeText(
                this,
                "Couldn't start a scan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun wifiConnect(ap: WifiAP) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ap.name)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                logViewModel.addLog("Connected to ${ap.name}")
                apViewModel.connectedAP.value = ap.name
                apViewModel.clearAccessPoints()
                connectivityManager.bindProcessToNetwork(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                val addresses = linkProperties.linkAddresses
                addresses.forEach {
                    if (it.address is Inet4Address) { // Filter for IPv4 address
                        logViewModel.addLog("got dhcp IPv4: ${it.address.hostAddress}")
                        apViewModel.isConnected.value = true
                        apViewModel.localIP.value = it.address.hostAddress
                    }
                }
                val dnsServers = linkProperties.dnsServers
                if (dnsServers.isNotEmpty()) {
                    val firstDnsServer = dnsServers[0].hostAddress
                    logViewModel.addLog("First DNS Server: $firstDnsServer")
                    apViewModel.remoteIP.value = firstDnsServer
                    sendUdpPacket(dnsServers[0],Constants.CMD_PORT, CMD.INFO)
                } else {
                    logViewModel.addLog("No DNS servers available")
                }
            }
            override fun onUnavailable() {
                super.onUnavailable()
                logViewModel.addLog("Failed to connect to ${ap.name}")
                apViewModel.isConnected.value = false
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                logViewModel.addLog("Lost connection to ${ap.name}")
                apViewModel.isConnected.value = false
            }
        }

        connectivityManager.requestNetwork(request, callback)
    }

    private fun checkDeviceByIP(ipString: String){
        val ip = convertToInetAddress(ipString)
        if(ip == null){
            Toast.makeText(
                applicationContext,
                "Wrong ip: $ipString",
                Toast.LENGTH_LONG
            ).show()
        } else {
            sendUdpPacket(ip,Constants.CMD_PORT,CMD.INFO)
        }
    }

    fun convertToInetAddress(ipInput: String): InetAddress? {
        return try {
            InetAddress.getByName(ipInput)
        } catch (e: Exception) {
            null
        }
    }
    private fun sendUdpRaw(ip: InetAddress, port: Int, cmdStr: String, label: String) {
        Thread {
            try {
                val buffer = cmdStr.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, ip, port)
                DatagramSocket().use { socket ->
                    socket.send(packet)
                    socket.soTimeout = 2000
                    val receiveBuffer = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    try {
                        socket.receive(receivePacket)
                        val resp = String(receivePacket.data, 0, receivePacket.length).trim()
                        logViewModel.addLog("$label: $resp")
                    } catch (e: SocketTimeoutException) {
                        logViewModel.addLog("$label: timeout")
                    }
                }
            } catch (e: Exception) {
                logViewModel.addLog("$label error: ${e.message}")
            }
        }.start()
    }

    private fun wifiReadConfig() {
        val ip = convertToInetAddress(apViewModel.remoteIP.value) ?: run {
            logViewModel.addLog("Invalid IP"); return
        }
        sendUdpRaw(ip, Constants.CMD_PORT, "AT+WSSSID\r", "SSID")
        Thread.sleep(300)
        sendUdpRaw(ip, Constants.CMD_PORT, "AT+WSKEY\r", "KEY")
        Thread.sleep(300)
        sendUdpRaw(ip, Constants.CMD_PORT, "AT+WMODE\r", "MODE")
    }

    private fun wifiSetAndReboot() {
        val ip = convertToInetAddress(apViewModel.remoteIP.value) ?: run {
            logViewModel.addLog("Invalid IP"); return
        }
        val ssid = apViewModel.wifiSsid.value
        val pass = apViewModel.wifiPassword.value
        if (ssid.isEmpty()) { logViewModel.addLog("SSID is empty"); return }
        Thread {
            val cmds = listOf(
                "AT+WSSSID=$ssid\r" to "SET SSID",
                "AT+WSKEY=WPA2PSK,AES,$pass\r" to "SET KEY",
                "AT+WMODE=STA\r" to "SET MODE",
                "AT+Z\r" to "REBOOT"
            )
            for ((cmdStr, label) in cmds) {
                try {
                    val buffer = cmdStr.toByteArray()
                    val packet = DatagramPacket(buffer, buffer.size, ip, Constants.CMD_PORT)
                    DatagramSocket().use { socket ->
                        socket.send(packet)
                        socket.soTimeout = 2000
                        val receiveBuffer = ByteArray(1024)
                        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        try {
                            socket.receive(receivePacket)
                            val resp = String(receivePacket.data, 0, receivePacket.length).trim()
                            logViewModel.addLog("$label: $resp")
                        } catch (e: SocketTimeoutException) {
                            logViewModel.addLog("$label: timeout")
                        }
                    }
                } catch (e: Exception) {
                    logViewModel.addLog("$label error: ${e.message}")
                }
                Thread.sleep(400)
            }
        }.start()
    }

    private fun flashDevice(cmd: CMD = CMD.OTA) {
        val inetAddress = convertToInetAddress(apViewModel.remoteIP.value)
        if (inetAddress != null) {
            logViewModel.addLog("Flash cmd: ${cmd.name}")
            sendUdpPacket(inetAddress, Constants.CMD_PORT, cmd)
        } else {
            logViewModel.addLog("Invalid IP address: ${apViewModel.remoteIP.value}")
        }
    }

    private fun flashDeviceTcpOta() {
        val ip = apViewModel.remoteIP.value
        if (convertToInetAddress(ip) == null) {
            logViewModel.addLog("Invalid IP address: $ip")
            return
        }

        Thread {
            try {
                val localIp = apViewModel.localIP.value.ifBlank { "10.10.123.4" }
                val otaUrl = "http://$localIp:${Constants.OTA_PORT}/update?version=beta&x"
                val sn = System.currentTimeMillis().toString()
                val json = """{"sn":"$sn","cmd":5,"pv":0,"msg":{"url":"$otaUrl"}}"""
                logViewModel.addLog("TCP OTA: connecting to $ip:5555")

                Socket(ip, 5555).use { socket ->
                    socket.soTimeout = 30000
                    val output = socket.getOutputStream()
                    output.write("$json\r\n".toByteArray())
                    output.flush()
                    logViewModel.addLog("TCP OTA: sent cmd=5 url=$otaUrl")

                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    try {
                        while (true) {
                            val line = input.readLine() ?: break
                            logViewModel.addLog("TCP OTA: $line")
                            if (line.contains("\"res\"") || line.contains("\"sch\"")) {
                                break
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        logViewModel.addLog("TCP OTA: timeout waiting for response")
                    }
                }
            } catch (e: Exception) {
                logViewModel.addLog("TCP OTA error: ${e.message}")
                mainThread { apViewModel.flashPhase.value = FlashPhase.ERROR }
            }
        }.start()
    }

    private fun sendUdpPacket(ip: InetAddress, port: Int, cmd: CMD) {
        val thread = Thread {
            try {
                val buffer = cmd.str.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, ip, port)
                DatagramSocket().use { socket ->
                    socket.send(packet)
                    val receiveBuffer = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

                    val isOta = cmd == CMD.OTA || cmd == CMD.OTA_BETA || cmd == CMD.OTA_BETA2 || cmd == CMD.OTA_JSON
                    socket.soTimeout = if (isOta) 30000 else 2000

                    try {
                        if (!isOta) {
                            socket.receive(receivePacket)
                            val receivedText = String(receivePacket.data, 0, receivePacket.length)
                            when (cmd) {
                                CMD.INFO -> {
                                    checkUDP1_a11(receivedText)
                                    sendUdpPacket(ip, port, CMD.VER)
                                }
                                CMD.VER -> {
                                    logViewModel.addLog("ver: $receivedText")
                                    apViewModel.firmwareVersion.value = receivedText.split("=")[1]
                                }
                                else -> logViewModel.addLog("unk: $receivedText")
                            }
                        } else {
                            val terminalResponses = setOf(
                                "up_success", "up_failed", "up_ErrVer", "up_ErrHttp",
                                "up_ErrType", "up_ErrAes", "up_ErrCheck", "up_ErrTimeout", "up_ErrHEADER"
                            )
                            while (true) {
                                socket.receive(receivePacket)
                                val receivedText = String(receivePacket.data, 0, receivePacket.length).trim()
                                logViewModel.addLog("OTA: $receivedText")
                                if (terminalResponses.any { receivedText.contains(it) }) break
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        if (isOta)
                            logViewModel.addLog("OTA: timeout (no response from device)")
                        else
                            logViewModel.addLog("Timeout: No response received within 2 seconds")
                    }
                }
            } catch (e: Exception) {
                logViewModel.addLog("Error sending or receiving packet: ${e.message}")
            }
        }
        thread.start()
    }

    private fun checkUDP1_a11(str: String){
        logViewModel.addLog("Received: $str")
        val parts=str.split(",")
        if (parts.count() > 2){
            logViewModel.addLog("\tmac:${parts[1]}")
            apViewModel.macAddress.value=parts[1]
            logViewModel.addLog("\tdev id:${parts[2]}")
            apViewModel.deviceId.value=parts[2]
        }
    }

    private fun startHttpServer(context: Context) {
        Thread {
            try {
                val serverSocket = ServerSocket(Constants.OTA_PORT)
                logViewModel.addLog("OTA server is running on port ${Constants.OTA_PORT}")

                while (!Thread.currentThread().isInterrupted) {
                    val socket = serverSocket.accept()
                    try {
                        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val output = BufferedOutputStream(socket.getOutputStream())

                        val requestLines = mutableListOf<String>()
                        var line: String?
                        while (input.readLine().also { line = it } != null && line != "") {
                            requestLines.add(line!!)
                        }
                        logViewModel.addLog("Received HTTP Request:")
                        requestLines.forEach { logViewModel.addLog(it) }

                        val bytes = apViewModel.otaBytes
                        if (bytes == null) {
                            logViewModel.addLog("OTA not ready — rejecting request")
                            socket.close()
                            continue
                        }

                        mainThread { apViewModel.flashProgress.value = 0f }
                        mainThread { apViewModel.flashPhase.value = FlashPhase.UPLOADING }

                        val fileSize = bytes.size
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: $fileSize\r\n\r\n".toByteArray())

                        val chunkSize = 1024 * 10
                        var totalSentBytes = 0
                        var offset = 0
                        while (offset < bytes.size) {
                            val end = minOf(offset + chunkSize, bytes.size)
                            output.write(bytes, offset, end - offset)
                            totalSentBytes += end - offset
                            offset = end
                            val progress = totalSentBytes.toFloat() / fileSize
                            logViewModel.addLog("Sent $totalSentBytes of $fileSize bytes")
                            mainThread { apViewModel.flashProgress.value = progress }
                        }
                        logViewModel.addLog("Finished uploading $totalSentBytes bytes")
                        mainThread { apViewModel.flashPhase.value = FlashPhase.DONE }
                        output.flush()
                        output.close()
                        input.close()
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }

                serverSocket.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }
    private fun mainThread(block: () -> Unit) =
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)

    private fun prepareOtaAndFlash(
        context: Context,
        trigger: OtaTrigger,
        cmd: CMD = CMD.OTA_BETA
    ) {
        mainThread { apViewModel.flashPhase.value = FlashPhase.PATCHING }
        Thread {
            try {
                logViewModel.addLog("Preparing firmware: patching config...")
                val rawPayload = context.assets.open("OpenBL602_small_20260624_9_OTA.bin").readBytes()
                val headerTemplate = context.assets.open("OpenBL602_dev_20260618_214208_OTA_zengge.bin.xz.ota").readBytes()

                mainThread { apViewModel.flashPhase.value = FlashPhase.COMPRESSING }
                logViewModel.addLog("Compressing (XZ)... this may take a few seconds")
                val built = OtaPatcher.buildOtaFile(
                    rawPayload, headerTemplate,
                    apViewModel.wifiSsid.value,
                    apViewModel.wifiPassword.value,
                    apViewModel.wifiHostname.value,
                    log = { logViewModel.addLog(it) }
                )
                apViewModel.otaBytes = built
                logViewModel.addLog("Firmware ready: ${built.size} bytes")
                val otaPayloadLen = readLe32(built, 0x14)
                if (trigger != OtaTrigger.AT_UPURL && otaPayloadLen > Constants.TCP_OTA_FW_LIMIT) {
                    logViewModel.addLog(
                        "TCP OTA image too large: payload $otaPayloadLen > FW ${Constants.TCP_OTA_FW_LIMIT} bytes"
                    )
                    mainThread { apViewModel.flashPhase.value = FlashPhase.ERROR }
                    return@Thread
                }

                // save for inspection: adb pull /sdcard/Android/data/com.lance3.mhflasher/files/mhflasher_patched.bin.xz.ota
                try {
                    val outFile = java.io.File(context.getExternalFilesDir(null), "mhflasher_patched.bin.xz.ota")
                    outFile.writeBytes(built)
                    logViewModel.addLog("Saved: adb pull ${outFile.absolutePath}")
                } catch (e: Exception) {
                    logViewModel.addLog("Save failed: ${e.message}")
                }

                mainThread { apViewModel.flashPhase.value = FlashPhase.READY }
                when (trigger) {
                    OtaTrigger.AT_UPURL -> flashDevice(cmd)
                    OtaTrigger.TCP_OTA -> flashDeviceTcpOta()
                }
            } catch (e: Exception) {
                logViewModel.addLog("Firmware preparation failed: ${e.message}")
                mainThread { apViewModel.flashPhase.value = FlashPhase.ERROR }
            }
        }.start()
    }

    private fun myLog(message: String){
        logViewModel.addLog(message)
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}

@Composable
fun MyApp(
    logViewModel: LogViewModel,
    apViewModel: ApViewModel,
    onScanClick: () -> Unit,
    onConnectClick: (WifiAP) -> kotlin.Unit,
    onCheckDeviceClick: (String) -> Unit,
    onFlashClick: (CMD) -> Unit,
    onFlashTcpOtaClick: (OtaTrigger) -> Unit,
    onWifiReadClick: () -> Unit,
    onWifiSetClick: () -> Unit,
    selTab: Int = 0
) {
    var tabIndex by remember { mutableIntStateOf(selTab) }
    val tabTitles = listOf("flash", "log", "about")
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(title) })
            }
        }
        when (tabIndex) {
            0 -> MainTabContent(logViewModel,apViewModel,onScanClick,onConnectClick, onCheckDeviceClick, onFlashClick, onFlashTcpOtaClick, onWifiReadClick, onWifiSetClick)
            1 -> LogTabContent(logViewModel)
        }
    }
}

@Composable
fun FlashStatusRow(label: String, indeterminate: Boolean, progress: Float = 0f) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (indeterminate) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.width(20.dp).height(20.dp),
                strokeWidth = 2.dp
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

fun signalBars(rssi: Int): String = when {
    rssi >= -50 -> "▂▄▆█"
    rssi >= -65 -> "▂▄▆·"
    rssi >= -75 -> "▂▄··"
    else        -> "▂···"
}

@Composable
fun MainTabContent(
    logViewModel: LogViewModel,
    apViewModel: ApViewModel,
    onScanClick: () -> Unit,
    onConnectClick: (WifiAP) -> Unit,
    onCheckDeviceClick: (String) -> Unit,
    onFlashClick: (CMD) -> Unit,
    onFlashTcpOtaClick: (OtaTrigger) -> Unit,
    onWifiReadClick: () -> Unit,
    onWifiSetClick: () -> Unit
) {
    val ssid = apViewModel.wifiSsid.value
    val autoConfig = ssid.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Device discovery card ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Device discovery", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))

                val uniqueAPs = apViewModel.accessPoints
                    .groupBy { it.name }
                    .map { (_, list) -> list.maxBy { it.rssi } }
                    .sortedByDescending { it.rssi }

                if (uniqueAPs.isEmpty()) {
                    Text(
                        "No devices found. Tap Scan to search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    uniqueAPs.forEach { ap ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConnectClick(ap) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ap.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = signalBars(ap.rssi) + "  ${ap.rssi} dBm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { logViewModel.addLog("clicked scan"); onScanClick() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan for WiFi device")
                }
                DeviceDetailsTable(apViewModel)
            }
        }

        if (apViewModel.firmwareVersion.value.isNotEmpty() || apViewModel.remoteIP.value.isNotEmpty()) {

            // --- Flash card ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Target WiFi network", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Fill SSID and password to pre-configure WiFi — after flashing the device will automatically connect to this network. Leave empty to use the standard OpenBeken pairing procedure (AP mode).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = apViewModel.wifiSsid.value,
                        onValueChange = { apViewModel.wifiSsid.value = it; apViewModel.saveWifiConfig() },
                        label = { Text("SSID") },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apViewModel.wifiPassword.value,
                        onValueChange = { apViewModel.wifiPassword.value = it; apViewModel.saveWifiConfig() },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apViewModel.wifiHostname.value,
                        onValueChange = { apViewModel.wifiHostname.value = it; apViewModel.saveWifiConfig() },
                        label = { Text("Hostname (optional)") },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Mode banner
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (autoConfig) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (autoConfig) "After flash: auto-connect to \"$ssid\""
                                   else "After flash: standard OpenBeken pairing (AP mode)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (autoConfig) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    Divider()

                    val phase = apViewModel.flashPhase.value
                    val isFlashing = phase != FlashPhase.IDLE && phase != FlashPhase.DONE && phase != FlashPhase.ERROR

                    Button(
                        onClick = { if (!isFlashing) onFlashClick(CMD.OTA_BETA) },
                        enabled = !isFlashing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (autoConfig) "Flash OpenBeken + WiFi config (AT)" else "Flash OpenBeken (AT)")
                    }

                    FilledTonalButton(
                        onClick = { if (!isFlashing) onFlashTcpOtaClick(OtaTrigger.TCP_OTA) },
                        enabled = !isFlashing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (autoConfig) "Flash OpenBeken + WiFi config (CozyLife TCP/5555)" else "Flash OpenBeken (CozyLife TCP/5555)")
                    }

                    when (phase) {
                        FlashPhase.PATCHING -> {
                            FlashStatusRow("1/3  Patching firmware config...", indeterminate = true)
                        }
                        FlashPhase.COMPRESSING -> {
                            FlashStatusRow("2/3  Compressing (XZ)...", indeterminate = true)
                        }
                        FlashPhase.READY -> {
                            FlashStatusRow("3/3  Sending command to device...", indeterminate = true)
                        }
                        FlashPhase.UPLOADING -> {
                            FlashStatusRow(
                                "Uploading firmware  ${(apViewModel.flashProgress.value * 100).toInt()}%",
                                indeterminate = false,
                                progress = apViewModel.flashProgress.value
                            )
                        }
                        FlashPhase.DONE -> {
                            Text(
                                "Upload complete. Device is rebooting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        FlashPhase.ERROR -> {
                            Text(
                                "Error — check log tab for details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            }

            // --- Live AT config card ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live WiFi config (AT commands)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Read or set WiFi credentials on an already-running OpenBeken device via UDP AT commands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onWifiReadClick() }, modifier = Modifier.weight(1f)) {
                            Text("Read config")
                        }
                        FilledTonalButton(onClick = { onWifiSetClick() }, modifier = Modifier.weight(1f)) {
                            Text("Set & Reboot")
                        }
                    }
                }
            }
        }
    }
}
/*
Text("or", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
OutlinedTextField(
    value = apViewModel.remoteIP.value,
    onValueChange = { newValue ->
        apViewModel.remoteIP.value = newValue
    },
    label = { Text("Enter device IP") },
    singleLine = true,
    textStyle = TextStyle(color = Color.Black),
    keyboardOptions = KeyboardOptions.Default.copy(
        keyboardType = KeyboardType.Decimal,
        autoCorrect = false
    )
)
Button(onClick = { onCheckDeviceClick(apViewModel.remoteIP.value) }) {
    Text(text = "Check device")
}
 */

@Composable
fun LogTabContent(logViewModel: LogViewModel) {

    Column {
        BasicTextField(
            value = logViewModel.logText.value,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            readOnly = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { logViewModel.clearLog() }) {
            Text("clear")
        }
    }
}

@Composable
fun AboutTabContent(){

}
@Composable
fun DeviceDetailsTable(apViewModel: ApViewModel) {
    if (apViewModel.isConnected.value) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Connected Device Details")

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("AP:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(apViewModel.connectedAP.value)
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("MAC Address:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(apViewModel.macAddress.value)
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("Device ID:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(apViewModel.deviceId.value)
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("Firmware Version:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(apViewModel.firmwareVersion.value)
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("Device IP:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(apViewModel.remoteIP.value)
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text("My IP:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(apViewModel.localIP.value)
            }
        }
    }
}

//@Preview(showBackground = true)
//@Composable
//fun DefaultPreview(onScanClick: () -> Unit) {
//    val logViewModel = LogViewModel()
//    logViewModel.addLog("First line")
//    logViewModel.addLog("Second line")
//    val apViewModel = ApViewModel()
//    MagicHomeFlasherTheme {
//        MyApp(logViewModel, apViewModel, onScanClick, onConnectClick ,0)
//    }
//}
