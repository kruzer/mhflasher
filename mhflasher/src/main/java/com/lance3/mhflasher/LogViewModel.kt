import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class LogViewModel : ViewModel() {
    var logText = mutableStateOf("")
    fun addLog(message: String) {
        val currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val newMessage =
            if (logText.value.isEmpty()) "[$currentTime] $message" else "${logText.value}\n[$currentTime] $message"
        logText.value = newMessage
    }

    fun clearLog() {
        logText.value = ""
    }
}
