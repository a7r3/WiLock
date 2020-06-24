package nooblife.lockit.wilock

import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.cardview.widget.CardView
import androidx.preference.PreferenceManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.util.concurrent.Executors


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
    private lateinit var currentPayload: Payload

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

    // Just a sender, not required to listen
    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(p0: String, p1: Payload) {
        }

        override fun onPayloadTransferUpdate(p0: String, p1: PayloadTransferUpdate) {
        }
    }

    val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionResult(p0: String, p1: ConnectionResolution) {
            when (p1.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    showToast("Connection Successful")
                    setConnectionSuccessful()
                    Nearby.getConnectionsClient(this@MainActivity)
                        .sendPayload(p0, currentPayload).addOnSuccessListener {
                            showToast("${currentPayload.asBytes().toString()} SENT")
                            Nearby.getConnectionsClient(this@MainActivity)
                                .disconnectFromEndpoint(p0)
                            Nearby.getConnectionsClient(this@MainActivity)
                                .stopDiscovery()
                            currentState = State.IDLE
                        }
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    showToast("Connection failed")
                    setConnectionFailed()
                }
            }
        }

        override fun onDisconnected(p0: String) {
            Log.d(TAG, "onDisconnected: $p0")
        }

        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (authenticationCode.isEmpty()) {
                authenticationCode = connectionInfo.authenticationToken
                sharedPreferences.edit().putString(Util.PREF_LOCKIT_RC_SERVICE_ID, authenticationCode).apply()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Accept connection to " + connectionInfo.endpointName)
                    .setMessage("Confirm the code matches on both devices: ${connectionInfo.authenticationToken}")
                    .setPositiveButton("Accept") { _: DialogInterface, i: Int ->
                        Nearby.getConnectionsClient(this@MainActivity)
                            .acceptConnection(endpointId, payloadCallback)
                    }
                    .setNegativeButton(android.R.string.cancel) { _: DialogInterface, _: Int ->
                        Nearby.getConnectionsClient(this@MainActivity)
                            .rejectConnection(endpointId)
                    }
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show()
            } else {
                Log.d(TAG, "onConnectionInitiated: accepting connection")
                Nearby.getConnectionsClient(this@MainActivity)
                    .acceptConnection(endpointId, payloadCallback)
            }
        }
    }

    val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(p0: String, p1: DiscoveredEndpointInfo) {
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection("remotelocker", p0, connectionLifecycleCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "onEndpointFound: success")
                }
                .addOnFailureListener {
                    it.printStackTrace()
                    Log.d(TAG, "onEndpointFound: failure")
                };
        }

        override fun onEndpointLost(p0: String) {
            Log.d(TAG, "onEndpointLost: $p0")
        }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(tvDedicatedServiceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                Log.d(TAG, "startDiscovery: STARTED $tvDedicatedServiceId")
            }
            .addOnFailureListener {
                it.printStackTrace()
                Log.d(TAG, "startDiscovery: FAILURE")
            };
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
                    currentPayload = Payload.fromBytes(action.toByteArray())
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