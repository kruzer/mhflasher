package com.lance3.mhflasher

import ApViewModel
import FirmwareImage
import LogViewModel
import TargetMcu
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.LaunchedEffect
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
    const val LN882H_OTA_ASSET = "OpenLN8825_1.18.295_ota.img"
}

enum class CMD(val str: String){
    INFO("HF-A11ASSISTHREAD"),
    VER("AT+LVER\n"),
    OTA("AT+UPURL=http://10.10.123.4:${Constants.OTA_PORT}/update?version=33_00_dev_20260625_142543_OpenBeken&beta,${Constants.CODE_PLACEHOLDER}"),
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
                MyApp(logViewModel, apViewModel, {wifiCheckAndScan()}, {ap -> wifiConnect(ap)},{ip -> checkDeviceByIP(ip)}, {trigger -> prepareOtaAndFlash(applicationContext, trigger)}, {wifiReadConfig()}, {wifiSetAndReboot()})
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
                var localIp: String? = null
                val addresses = linkProperties.linkAddresses
                addresses.forEach {
                    if (it.address is Inet4Address) { // Filter for IPv4 address
                        logViewModel.addLog("got dhcp IPv4: ${it.address.hostAddress}")
                        apViewModel.isConnected.value = true
                        localIp = it.address.hostAddress
                        apViewModel.localIP.value = it.address.hostAddress
                    }
                }
                val firstDnsServer = linkProperties.dnsServers.firstOrNull()?.hostAddress
                val dnsServers = linkProperties.dnsServers
                val gateway = linkProperties.routes
                    .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway
                    ?.hostAddress
                val deviceIp = gateway ?: localIp?.substringBeforeLast('.')?.let { "$it.1" }
                if (deviceIp != null) {
                    logViewModel.addLog("Device gateway IP: $deviceIp${if (firstDnsServer != null) " (DNS=$firstDnsServer ignored)" else ""}")
                    apViewModel.remoteIP.value = deviceIp
                    val deviceAddress = convertToInetAddress(deviceIp)
                    if (deviceAddress != null && apViewModel.targetMcu.value == TargetMcu.BL602 && !isCozyLifeAp(ap.name)) {
                        sendUdpPacket(deviceAddress, Constants.CMD_PORT, CMD.INFO)
                    } else {
                        logViewModel.addLog("Skipping UDP AT discovery for TCP-only device path")
                    }
                } else {
                    logViewModel.addLog("No device gateway IP available")
                    if (dnsServers.isEmpty()) {
                        logViewModel.addLog("No DNS servers available")
                    }
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

    private fun flashDevice() {
        val inetAddress = convertToInetAddress(apViewModel.remoteIP.value)
        if (inetAddress != null) {
            val cmd = buildAtOtaCommand()
            logViewModel.addLog("Flash cmd: $cmd")
            sendUdpPacket(inetAddress, Constants.CMD_PORT, cmd, isOta = true)
        } else {
            logViewModel.addLog("Invalid IP address: ${apViewModel.remoteIP.value}")
        }
    }

    private fun buildAtOtaCommand(): String {
        val localIp = apViewModel.localIP.value.ifBlank { "10.10.123.4" }
        return "AT+UPURL=http://$localIp:${Constants.OTA_PORT}/update?version=beta_33_00_ZG-BL&beta,${Constants.CODE_PLACEHOLDER}"
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
        sendUdpPacket(
            ip = ip,
            port = port,
            cmd = cmd.str,
            isOta = cmd == CMD.OTA || cmd == CMD.OTA_BETA || cmd == CMD.OTA_BETA2 || cmd == CMD.OTA_JSON,
            enumCmd = cmd
        )
    }

    private fun sendUdpPacket(ip: InetAddress, port: Int, cmd: String, isOta: Boolean, enumCmd: CMD? = null) {
        val thread = Thread {
            try {
                val buffer = cmd.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, ip, port)
                DatagramSocket().use { socket ->
                    socket.send(packet)
                    val receiveBuffer = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

                    socket.soTimeout = if (isOta) 30000 else 2000

                    try {
                        if (!isOta) {
                            socket.receive(receivePacket)
                            val receivedText = String(receivePacket.data, 0, receivePacket.length)
                            when (enumCmd) {
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
        trigger: OtaTrigger
    ) {
        mainThread { apViewModel.flashPhase.value = FlashPhase.PATCHING }
        Thread {
            try {
                when (apViewModel.targetMcu.value) {
                    TargetMcu.BL602 -> {
                        val image = apViewModel.firmwareImage.value
                        val rawPayloadAsset = when (image) {
                            FirmwareImage.STANDARD -> "OpenBL602_dev_20260625_142543_OTA.bin"
                            FirmwareImage.SMALL -> "OpenBL602_small_repart3_OTA.bin"
                        }
                        logViewModel.addLog("Preparing BL602 firmware: $image, patching config...")
                        val rawPayload = context.assets.open(rawPayloadAsset).readBytes()
                        val headerTemplate = context.assets.open("bl602_zengge_ota_header.bin").readBytes()

                        mainThread { apViewModel.flashPhase.value = FlashPhase.COMPRESSING }
                        logViewModel.addLog("Compressing (XZ)... this may take a few seconds")
                        val injectWifi = apViewModel.preconfigureWifi.value
                        val built = OtaPatcher.buildOtaFile(
                            rawPayload, headerTemplate,
                            if (injectWifi) apViewModel.wifiSsid.value else "",
                            if (injectWifi) apViewModel.wifiPassword.value else "",
                            if (injectWifi) apViewModel.wifiHostname.value else "",
                            log = { logViewModel.addLog(it) }
                        )
                        apViewModel.otaBytes = built
                        logViewModel.addLog("BL602 firmware ready: ${built.size} bytes")
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
                            OtaTrigger.AT_UPURL -> flashDevice()
                            OtaTrigger.TCP_OTA -> flashDeviceTcpOta()
                        }
                    }

                    TargetMcu.LN882H -> {
                        logViewModel.addLog("Preparing LN882H OTA image...")
                        val raw = context.assets.open(Constants.LN882H_OTA_ASSET).readBytes()
                        val img = LnOtaImage.normalize(raw) { logViewModel.addLog(it) }
                        val validationError = LnOtaImage.validate(img)
                        if (validationError != null) {
                            logViewModel.addLog("LN882H OTA image invalid: $validationError")
                            mainThread { apViewModel.flashPhase.value = FlashPhase.ERROR }
                            return@Thread
                        }

                        apViewModel.otaBytes = img
                        logViewModel.addLog("LN882H image ready: ${img.size} bytes")
                        mainThread { apViewModel.flashPhase.value = FlashPhase.READY }
                        flashDeviceTcpOta()
                    }
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
    onFlashClick: (OtaTrigger) -> Unit,
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
            0 -> MainTabContent(logViewModel,apViewModel,onScanClick,onConnectClick, onCheckDeviceClick, onFlashClick, onWifiReadClick, onWifiSetClick)
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
    onFlashClick: (OtaTrigger) -> Unit,
    onWifiReadClick: () -> Unit,
    onWifiSetClick: () -> Unit
) {
    val connectedAp = apViewModel.connectedAP.value
    val looksCozyLife = isCozyLifeAp(connectedAp)
    val targetMcu = apViewModel.targetMcu.value
    var flashMethod by remember(connectedAp) {
        mutableStateOf(if (looksCozyLife) OtaTrigger.TCP_OTA else OtaTrigger.AT_UPURL)
    }
    val preconfigureWifi = apViewModel.preconfigureWifi.value
    val targetSsid = apViewModel.wifiSsid.value
    val suggestedHostname = suggestedHostnameFromAp(connectedAp)
    val hasDeviceConnection = apViewModel.remoteIP.value.isNotEmpty() || apViewModel.localIP.value.isNotEmpty()
    val phase = apViewModel.flashPhase.value
    val isFlashing = phase != FlashPhase.IDLE && phase != FlashPhase.DONE && phase != FlashPhase.ERROR

    LaunchedEffect(connectedAp) {
        if (connectedAp.isNotBlank() && apViewModel.wifiHostname.value.isBlank()) {
            apViewModel.wifiHostname.value = suggestedHostname
            apViewModel.saveWifiConfig()
        }
        if (connectedAp.isNotBlank()) {
            apViewModel.firmwareImage.value = if (looksCozyLife) FirmwareImage.SMALL else FirmwareImage.STANDARD
        }
    }

    LaunchedEffect(targetMcu) {
        if (targetMcu == TargetMcu.LN882H) {
            flashMethod = OtaTrigger.TCP_OTA
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("1. Connect") {
            val uniqueAPs = apViewModel.accessPoints
                .groupBy { it.name }
                .map { (_, list) -> list.maxBy { it.rssi } }
                .sortedByDescending { it.rssi }

            if (uniqueAPs.isEmpty()) {
                Text(
                    if (connectedAp.isBlank()) "No devices found. Scan for nearby BL602/LN882H access points."
                    else "Connected to $connectedAp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                uniqueAPs.forEach { ap ->
                    AccessPointRow(ap = ap, selected = ap.name == connectedAp) {
                        onConnectClick(ap)
                    }
                }
            }

            Button(
                onClick = { logViewModel.addLog("clicked scan"); onScanClick() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan for WiFi devices")
            }
        }

        if (hasDeviceConnection) {
            SectionCard("2. Diagnose") {
                Text(
                    inferredDeviceSummary(connectedAp, apViewModel.firmwareVersion.value),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                KeyValueGrid(
                    "Device AP" to connectedAp.ifBlank { "-" },
                    "Device IP" to apViewModel.remoteIP.value.ifBlank { "-" },
                    "Phone IP" to apViewModel.localIP.value.ifBlank { "-" },
                    "Firmware" to apViewModel.firmwareVersion.value.ifBlank { "No AT response yet" },
                    "Device ID" to apViewModel.deviceId.value.ifBlank { "-" },
                    "MAC" to apViewModel.macAddress.value.ifBlank { "-" }
                )

                if (apViewModel.remoteIP.value.isNotBlank() && targetMcu == TargetMcu.BL602) {
                    FilledTonalButton(
                        onClick = { onCheckDeviceClick(apViewModel.remoteIP.value) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retry AT discovery")
                    }
                } else if (targetMcu == TargetMcu.LN882H) {
                    Text(
                        "LN882H uses TCP/5555; UDP AT discovery is skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard("3. Flash Plan") {
                Text("Target MCU", style = MaterialTheme.typography.titleSmall)
                OptionRow(
                    title = "BL602",
                    subtitle = "Magic Home, Zengge/ZJ, and BL602 CozyLife devices.",
                    selected = targetMcu == TargetMcu.BL602,
                    onClick = { apViewModel.targetMcu.value = TargetMcu.BL602 }
                )
                OptionRow(
                    title = "LN882H / LN8825",
                    subtitle = "Experimental CozyLife/DoHome path. Uses TCP/5555 and serves an LN OTA image as-is.",
                    selected = targetMcu == TargetMcu.LN882H,
                    onClick = {
                        apViewModel.targetMcu.value = TargetMcu.LN882H
                        flashMethod = OtaTrigger.TCP_OTA
                    }
                )

                Divider()

                Text("Start method", style = MaterialTheme.typography.titleSmall)
                OptionRow(
                    title = "AT commands over UDP",
                    subtitle = "Magic Home / Zengge path, port 48899, AT+UPURL.",
                    selected = flashMethod == OtaTrigger.AT_UPURL,
                    enabled = targetMcu == TargetMcu.BL602,
                    onClick = { flashMethod = OtaTrigger.AT_UPURL }
                )
                OptionRow(
                    title = "CozyLife TCP/5555",
                    subtitle = if (targetMcu == TargetMcu.LN882H) "Required for LN882H firmware." else "JSON cmd=5 trigger used by CozyLife firmware.",
                    selected = flashMethod == OtaTrigger.TCP_OTA,
                    onClick = { flashMethod = OtaTrigger.TCP_OTA }
                )

                Divider()

                if (targetMcu == TargetMcu.BL602) {
                    Text("Firmware image", style = MaterialTheme.typography.titleSmall)
                    OptionRow(
                        title = "Standard OpenBeken",
                        subtitle = "Full 20260625 build. Supports OTA header patching and WiFi injection.",
                        selected = apViewModel.firmwareImage.value == FirmwareImage.STANDARD,
                        onClick = { apViewModel.firmwareImage.value = FirmwareImage.STANDARD }
                    )
                    OptionRow(
                        title = "Small OpenBeken (1MB-safe)",
                        subtitle = "Smaller 20260625 build for CozyLife devices with limited flash.",
                        selected = apViewModel.firmwareImage.value == FirmwareImage.SMALL,
                        onClick = { apViewModel.firmwareImage.value = FirmwareImage.SMALL }
                    )
                } else {
                    Text("Firmware image", style = MaterialTheme.typography.titleSmall)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "OpenLN8825 1.18.295 OTA image. The app normalizes the LN image header before serving it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Divider()

                if (targetMcu == TargetMcu.BL602) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Preconfigure WiFi", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (preconfigureWifi) "OpenBeken will try to join the target network after flashing."
                                else "Leave off to use the normal OpenBeken AP pairing mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = preconfigureWifi,
                            onCheckedChange = {
                                apViewModel.preconfigureWifi.value = it
                                apViewModel.saveWifiConfig()
                            }
                        )
                    }

                    if (preconfigureWifi) {
                        OutlinedTextField(
                            value = apViewModel.wifiSsid.value,
                            onValueChange = { apViewModel.wifiSsid.value = it; apViewModel.saveWifiConfig() },
                            label = { Text("Target SSID") },
                            singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = apViewModel.wifiPassword.value,
                            onValueChange = { apViewModel.wifiPassword.value = it; apViewModel.saveWifiConfig() },
                            label = { Text("Target password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = apViewModel.wifiHostname.value,
                            onValueChange = { apViewModel.wifiHostname.value = it; apViewModel.saveWifiConfig() },
                            label = { Text("OpenBeken hostname") },
                            singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SummaryLine("Target", if (targetMcu == TargetMcu.LN882H) "LN882H / LN8825" else "BL602")
                        SummaryLine("Method", if (flashMethod == OtaTrigger.TCP_OTA) "CozyLife TCP/5555" else "AT UDP")
                        SummaryLine(
                            "Image",
                            if (targetMcu == TargetMcu.LN882H) "OpenLN8825"
                            else if (apViewModel.firmwareImage.value == FirmwareImage.SMALL) "Small OpenBeken"
                            else "Standard OpenBeken"
                        )
                        SummaryLine(
                            "WiFi",
                            if (targetMcu == TargetMcu.LN882H) "unchanged by flasher"
                            else if (preconfigureWifi && targetSsid.isNotBlank()) "preconfigure $targetSsid"
                            else "OpenBeken AP pairing"
                        )
                    }
                }

                Button(
                    onClick = { if (!isFlashing) onFlashClick(flashMethod) },
                    enabled = !isFlashing && (targetMcu == TargetMcu.LN882H || !preconfigureWifi || targetSsid.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (targetMcu == TargetMcu.LN882H) "Flash LN882H OpenBeken" else "Flash OpenBeken")
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
                            "Flash sent. Wait 15-30 seconds for the device to appear on WiFi. If it does not show up, power-cycle it manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    FlashPhase.ERROR -> {
                        Text(
                            "Error - check log tab for details.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }

            SectionCard("Advanced") {
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

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun AccessPointRow(ap: WifiAP, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ap.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (selected) {
                Text("Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = signalBars(ap.rssi) + "  ${ap.rssi} dBm",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

@Composable
fun OptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun KeyValueGrid(vararg items: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (key, value) ->
            SummaryLine(key, value)
        }
    }
}

@Composable
fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

fun isCozyLifeAp(apName: String): Boolean =
    apName.startsWith("CozyLife", ignoreCase = true)

fun inferredDeviceSummary(apName: String, firmwareVersion: String): String = when {
    isCozyLifeAp(apName) -> "CozyLife-like AP detected. TCP/5555 is selected by default; choose BL602 or LN882H/LN8825 depending on the hardware."
    firmwareVersion.isNotBlank() -> "AT commands responded. Magic Home / Zengge OTA path should be available."
    apName.isNotBlank() -> "WiFi connection is active, but AT diagnostics have not confirmed the firmware yet."
    else -> "Connect to a device AP to run basic diagnostics."
}

fun suggestedHostnameFromAp(apName: String): String {
    val normalized = apName
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(24)
    return if (normalized.isBlank()) "openbeken" else "obk-$normalized"
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
