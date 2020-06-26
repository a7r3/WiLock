package nooblife.lockit.wilock

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
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.cardview.widget.CardView
import androidx.preference.PreferenceManager
import com.github.druk.rx2dnssd.Rx2DnssdBindable
import com.google.android.gms.nearby.Nearby
import io.reactivex.schedulers.Schedulers
import java.io.*
import java.lang.Exception
import java.net.Socket
import java.util.*
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private val LOCK = "lock";
    private val UNLOCK = "unlock";
    private val TAG = javaClass.simpleName
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var tvService: String
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionIcon: ImageView
    private lateinit var connectionText: TextView
    private lateinit var connectionCard: CardView
    private var currentState: State = State.IDLE
    private lateinit var currentAction: String

    enum class State {
        IDLE, PROGRESS, NOT_INITIALIZED, FAILURE
    }

    fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    fun setProgress() {
        connectionProgress.visibility = View.VISIBLE
        connectionText.text = "${currentAction}ing TV"
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
        showToast("TV is paired")
        currentState = State.IDLE
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
        currentState = State.NOT_INITIALIZED
        currentAction = "pair"
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
        val tvServiceId = sharedPreferences.getString(Util.PREF_LOCKIT_RC_SERVICE_ID, Util.LOCKIT_DEFAULT_SERVICE_ID).toString()

        tvService = String.format(Util.LOCKIT_SERVICE_TEMPLATE, tvServiceId)
        if (tvServiceId == Util.LOCKIT_DEFAULT_SERVICE_ID) {
            setReadyToPair()
        } else {
            setConnectionSuccessful()
        }
    }

    fun connectToClient(host: String?, port: Int) {
        try {
            val socket = Socket(host, port)
            Log.d(TAG, "connectToClient: Connection to Server Success")
            val bufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
            val printWriter = PrintWriter(bufferedOutputStream)
            printWriter.println(currentAction)
            printWriter.flush()
            Log.d(TAG, "connectToClient: Data written to buffer")

            if (currentAction == "pair") {
                val bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val tvServiceId = bufferedReader.readLine()
                Log.d(TAG, "connectToClient: dedicated service id $tvServiceId")
                sharedPreferences.edit().putString(Util.PREF_LOCKIT_RC_SERVICE_ID, tvServiceId).apply()
            }

            bufferedOutputStream.close()
            socket.close()
            Log.d(TAG, "connectToClient: Connection done!")
            runOnUiThread { setConnectionSuccessful() }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { setConnectionFailed() }
        }
    }

    fun startDiscovery() {
        val rx2Dnssd = Rx2DnssdBindable(this)
        val browseDisposable = rx2Dnssd.browse(tvService, "local.")
            .compose(rx2Dnssd.resolve())
            .compose(rx2Dnssd.queryIPV4Records())
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe({
                Log.d(TAG, "startDiscovery: Registered successfully ${it.inet4Address}")
                connectToClient(it.inet4Address?.hostAddress, it.port)
            }, {
                it.printStackTrace()
            }, {

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
//                    if (!authenticationCode.isNullOrEmpty()) {
                        runOnUiThread { setProgress() }
//                    } else {
//                        showToast("Failure: Not connected to TV")
//                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showToast("Fingerprint enrollment failed")
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

}