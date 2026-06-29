import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel

enum class FlashPhase { IDLE, PATCHING, COMPRESSING, READY, UPLOADING, DONE, ERROR }
enum class FirmwareImage { STANDARD, SMALL }
enum class TargetMcu { BL602, LN882H }

class ApViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("mhflasher", Context.MODE_PRIVATE)

    val accessPoints = mutableStateListOf<WifiAP>()
    var isConnected = mutableStateOf(false)
    var connectedAP = mutableStateOf("")
    var macAddress = mutableStateOf("")
    var deviceId = mutableStateOf("")
    var firmwareVersion = mutableStateOf("")
    var localIP = mutableStateOf("")
    var remoteIP = mutableStateOf("")
    var flashProgress = mutableStateOf(0.0f)
    var flashPhase = mutableStateOf(FlashPhase.IDLE)
    var targetMcu = mutableStateOf(TargetMcu.BL602)
    var firmwareImage = mutableStateOf(FirmwareImage.SMALL)
    var otaBytes: ByteArray? = null
    var useNewOtaFormat = mutableStateOf(false)
    var preconfigureWifi = mutableStateOf(prefs.getBoolean("preconfigureWifi", false))
    var wifiSsid = mutableStateOf(prefs.getString("ssid", "") ?: "")
    var wifiPassword = mutableStateOf(prefs.getString("password", "") ?: "")
    var wifiHostname = mutableStateOf(prefs.getString("hostname", "") ?: "")

    fun saveWifiConfig() {
        prefs.edit()
            .putBoolean("preconfigureWifi", preconfigureWifi.value)
            .putString("ssid", wifiSsid.value)
            .putString("password", wifiPassword.value)
            .putString("hostname", wifiHostname.value)
            .apply()
    }

    fun addAccessPoint(ap: WifiAP) {
        accessPoints.add(ap)
    }
    fun clearAccessPoints() {
        accessPoints.clear()
    }
}

data class WifiAP(val name: String, val rssi: Int)
