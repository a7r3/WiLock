package nooblife.lockit.wilock

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.cardview.widget.CardView
import androidx.preference.PreferenceManager
import com.google.android.gms.nearby.Nearby
import java.io.BufferedOutputStream
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.properties.Delegates


class MainActivity : AppCompatActivity() {

    private val LOCK = "lock";
    private val UNLOCK = "unlock";
    private val TAG = javaClass.simpleName
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var authenticationCode: String
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionIcon: ImageView
    private lateinit var connectionText: TextView
    private lateinit var connectionCard: CardView
    private var currentState: State = State.IDLE
    private lateinit var tvDedicatedServiceId: String
    private lateinit var currentAction: String

    enum class State {
        IDLE, PROGRESS, CONNECTED, FAILURE
    }

    fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    fun setProgress() {
        connectionProgress.visibility = View.VISIBLE
        connectionText.text = "Connecting to TV"
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(Color.parseColor("#F9A825"))
        connectionCard.setOnClickListener {}
        currentState = State.PROGRESS
        startDiscovery()
    }

    fun setConnectionSuccessful() {
        connectionProgress.visibility = View.GONE
        connectionText.text = "Connected to TV"
        connectionIcon.visibility = View.VISIBLE
        connectionCard.setCardBackgroundColor(Color.parseColor("#4caf50"))
        Nearby.getConnectionsClient(this).stopDiscovery()
        currentState = State.CONNECTED
        connectionCard.setOnClickListener {}
    }

    fun setConnectionFailed() {
        connectionProgress.visibility = View.GONE
        connectionText.text = "Connection Failed. Tap to Retry"
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(Color.parseColor("#d32f2f"))
        currentState = State.FAILURE
        connectionCard.setOnClickListener {
            setProgress()
        }
    }

    fun setReadyToPair() {
        connectionProgress.visibility = View.GONE
        connectionText.text = "Tap to pair with your TV"
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(Color.parseColor("#d32f2f"))
        currentState = State.IDLE
        connectionCard.setOnClickListener {
            setProgress()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val lockView: View = findViewById(R.id.lock)
        lockView.setOnClickListener { authAndSend(LOCK) }
        val unlockView: View = findViewById(R.id.unlock)
        unlockView.setOnClickListener { authAndSend(UNLOCK) }

        connectionProgress = findViewById(R.id.connection_progress)
        connectionIcon = findViewById(R.id.connection_status_icon)
        connectionText = findViewById(R.id.connection_status_text)
        connectionCard = findViewById(R.id.tv_status)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        authenticationCode = sharedPreferences.getString(Util.PREF_LOCKIT_RC_SERVICE_ID, "").toString()

        if (authenticationCode.isEmpty()) {
            setReadyToPair()
            tvDedicatedServiceId = Util.LOCKIT_SERVICE_ID
        } else {
            tvDedicatedServiceId = authenticationCode
        }
    }

    fun connectToClient(nsdServiceInfo: NsdServiceInfo) {
        val socket = Socket(nsdServiceInfo.host, nsdServiceInfo.port)
        Log.d(TAG, "connectToClient: Connection to Server Success")
        val bufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
        val printWriter = PrintWriter(bufferedOutputStream)
        printWriter.write(currentAction)
        Log.d(TAG, "connectToClient: Data written to buffer")
        printWriter.close()
        bufferedOutputStream.close()
        socket.close()
        Log.d(TAG, "connectToClient: Connection done!")
        runOnUiThread { setConnectionSuccessful() }
    }

    val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(p0: NsdServiceInfo?, p1: Int) {
            Log.d(TAG, "onResolveFailed: $p1")
            setConnectionFailed()
        }

        override fun onServiceResolved(p0: NsdServiceInfo?) {
            if (p0?.serviceName.equals(serviceName)) {
                Log.d(TAG, "onServiceResolved: Same IP")
                return
            }

            if (p0 != null)
                Executors.newSingleThreadExecutor().run {  connectToClient(p0) }
        }
    }

    private lateinit var nsdManager: NsdManager
    private lateinit var serviceName: String
//    private lateinit var host: String
//    private var port by Delegates.notNull<Int>()

    fun startDiscovery() {
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)
        nsdManager.discoverServices(Util.LOCKIT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(p0: NsdServiceInfo?) {
                        if (p0?.serviceType.equals(Util.LOCKIT_DISCOVERY_SERVICE_ID)) {
                            serviceName = p0?.serviceType.toString()
                            nsdManager.resolveService(p0, resolveListener)
                        }
                    }

                    override fun onStopDiscoveryFailed(p0: String?, p1: Int) {
                        Log.d(TAG, "onStopDiscoveryFailed: $p0 $p1")
                    }

                    override fun onStartDiscoveryFailed(p0: String?, p1: Int) {
                        Log.d(TAG, "onStartDiscoveryFailed: $p0 $p1")
                    }

                    override fun onDiscoveryStarted(p0: String?) {
                        Log.d(TAG, "onDiscoveryStarted: $p0")
                    }

                    override fun onDiscoveryStopped(p0: String?) {
                        Log.d(TAG, "onDiscoveryStopped: $p0")
                    }

                    override fun onServiceLost(p0: NsdServiceInfo?) {
                        Log.d(TAG, "onServiceLost: " + p0?.serviceName)
                    }
                })
    }

    fun authAndSend(action: String) {
        if (currentState != State.IDLE) {
            showToast("Another request is in progress")
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setNegativeButtonText("Cancel")
            .setTitle("WiLock")
            .setDescription("Scan your fingerprint to $action your TV")
            .build()

        val biometricPrompt = BiometricPrompt(this,
            Executors.newSingleThreadExecutor(),
            object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showToast(errString.toString())
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    currentAction = action
                    if (!authenticationCode.isNullOrEmpty()) {
                        runOnUiThread { setProgress() }
                        showToast("Sent $action to TV")
                    } else {
                        showToast("Failure: Not connected to TV")
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showToast("Fingerprint enrollment failed")
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

}