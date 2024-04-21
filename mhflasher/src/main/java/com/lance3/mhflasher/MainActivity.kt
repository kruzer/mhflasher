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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
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
import java.net.SocketTimeoutException

object Constants {
    const val OTA_PORT = 1111
    const val CMD_PORT = 48899
    const val CODE_PLACEHOLDER = "pierogi"
}

enum class CMD(val str: String){
    INFO("HF-A11ASSISTHREAD"),
    VER("AT+LVER\n"),
    OTA("AT+UPURL=http://10.10.123.4:${Constants.OTA_PORT}/update?version=33_00_20240418_OpenBeken&beta,${Constants.CODE_PLACEHOLDER}")
}
class MainActivity : ComponentActivity() {
    private val logViewModel = LogViewModel()
    private val apViewModel = ApViewModel()
    companion object {
        const val MY_PERMISSIONS_REQUEST_LOCATION = 1
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LOG","On create")
        setContent {
            MagicHomeFlasherTheme {
                MyApp(logViewModel, apViewModel, {wifiCheckAndScan()}, {ap -> wifiConnect(ap)},{ip -> checkDeviceByIP(ip)}, {flashDevice()})
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
    private fun flashDevice() {
        val inetAddress = convertToInetAddress(apViewModel.remoteIP.value)
        if (inetAddress != null) {
            sendUdpPacket(inetAddress, Constants.CMD_PORT, CMD.OTA)
        } else {
            logViewModel.addLog("Invalid IP address: ${apViewModel.remoteIP.value}")
        }
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
                    socket.soTimeout = 2000

                    try {
                        socket.receive(receivePacket)
                        val receivedText = String(receivePacket.data, 0, receivePacket.length)
                        when (cmd){
                            CMD.INFO -> {
                                checkUDP1_a11(receivedText)
                                sendUdpPacket(ip, port, CMD.VER)
                            }
                            CMD.VER -> {
                                logViewModel.addLog("ver: $receivedText")
                                apViewModel.firmwareVersion.value= receivedText.split("=")[1]
//                                sendUdpPacket(ip,port, CMD.OTA)
                            }
                            else -> {
                                logViewModel.addLog("unk: $receivedText")
                            }
                        }

                    } catch (e: SocketTimeoutException) {
                        val info="Timeout: No response received within 2 seconds"
                        logViewModel.addLog(info)
                    }
                }
            } catch (e: Exception) {
                val info="Error sending or receiving packet: ${e.message}"
                logViewModel.addLog(info)
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
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = BufferedOutputStream(socket.getOutputStream())

                    val requestLines = mutableListOf<String>()
                    var line: String?
                    while (input.readLine().also { line = it } != null && line != "") {
                        requestLines.add(line!!)
                    }
                    logViewModel.addLog("Received HTTP Request:")
                    apViewModel.flashProgress.value=0f
                    requestLines.forEach { logViewModel.addLog(it) }

                    //val fileInput = context.assets.open("OpenBL602_1.17.551_OTA.bin.xz.ota_cutted") //cutted=checksum error, test transfer only, no final flash
                    val fileInput = context.assets.open("OpenBL602_1.17.551_OTA.bin.xz.ota")
                    val fileSize = fileInput.available()
                    val httpResponse = "HTTP/1.0 200 OK\r\nContent-Length: $fileSize\r\n\r\n"

                    output.write(httpResponse.toByteArray())

                    val buffer = ByteArray(1024*10)
                    var totalSentBytes = 0
                    var bytesRead: Int
                    while (fileInput.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalSentBytes += bytesRead
                        logViewModel.addLog("Sent $totalSentBytes of $fileSize bytes")
                        apViewModel.flashProgress.value = totalSentBytes.toFloat()/fileSize
                    }
                    logViewModel.addLog("Finished uploading $totalSentBytes bytes")
                    output.flush()
                    output.close()
                    fileInput.close()
                    input.close()
                    socket.close()
                }

                serverSocket.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }
    private fun myLog(message: String){
        logViewModel.addLog(message)
    }
}

@Composable
fun MyApp(
    logViewModel: LogViewModel,
    apViewModel: ApViewModel,
    onScanClick: () -> Unit,
    onConnectClick: (WifiAP) -> kotlin.Unit,
    onCheckDeviceClick: (String) -> Unit,
    onFlashClick: () -> Unit,
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
            0 -> MainTabContent(logViewModel,apViewModel,onScanClick,onConnectClick, onCheckDeviceClick, onFlashClick)
            1 -> LogTabContent(logViewModel)
        }
    }
}

@Composable
fun MainTabContent(
    logViewModel: LogViewModel,
    apViewModel: ApViewModel,
    onScanClick: () -> Unit,
    onConnectClick: (WifiAP) -> Unit,
    onCheckDeviceClick: (String) -> Unit,
    onFlashClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn {
            items(apViewModel.accessPoints) { ap ->
                Text(
                    text = "SSID: ${ap.name}, RSSI: ${ap.rssi}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(0.dp, 10.dp)
                        .clickable {
                            onConnectClick(ap)
                        }
                )
            }
        }

        Button(onClick = {
            logViewModel.addLog("clicked scan")
            onScanClick()
        }) {
            Text(text = "Scan for WIFI device")
        }
        DeviceDetailsTable(apViewModel)

        if (apViewModel.firmwareVersion.value.isNotEmpty()) {
            Button(onClick = { onFlashClick() }) {
                Text(text = "Flash")
            }
        }

        if (apViewModel.flashProgress.value > 0) {
            LinearProgressIndicator(
                progress = apViewModel.flashProgress.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.background
            )
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
