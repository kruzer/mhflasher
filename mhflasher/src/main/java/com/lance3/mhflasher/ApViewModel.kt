import android.Manifest
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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel

class ApViewModel : ViewModel() {
    val accessPoints = mutableStateListOf<WifiAP>()
    fun addAccessPoint(ap: WifiAP) {
        accessPoints.add(ap)
    }
    fun clearAccessPoints() {
        accessPoints.clear()
    }

}

data class WifiAP(val name: String, val rssi: Int)
