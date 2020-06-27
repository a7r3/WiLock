package nooblife.lockit.wilock

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
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
import io.reactivex.schedulers.Schedulers
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private val TAG = javaClass.simpleName

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var tvService: String
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionIcon: ImageView
    private lateinit var connectionText: TextView
    private lateinit var connectionCard: CardView

    private val LOCK = "lock";
    private val UNLOCK = "unlock";
    private val PAIR = "pair";

    private lateinit var currentAction: String
    private lateinit var connectedTvName: String

    private var currentState: State = State.READY_TO_COMMAND

    enum class State {
        READY_TO_COMMAND, CONNECTION_ONGOING, READY_TO_PAIR
    }

    fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    fun startConnectionWithTv() {
        connectionProgress.visibility = View.VISIBLE
        connectionText.text = currentAction[0].toUpperCase() + currentAction.substring(1) + "ing " + connectedTvName
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(Color.parseColor("#F9A825"))
        connectionCard.setOnClickListener {}

        currentState = State.CONNECTION_ONGOING
        resolveAndConnectToClient()
    }

    fun setReadyToCommand() {
        connectionProgress.visibility = View.GONE
        connectionText.text = "Paired with $connectedTvName"
        connectionIcon.visibility = View.VISIBLE
        connectionCard.setCardBackgroundColor(Color.parseColor("#4caf50"))

        currentState = State.READY_TO_COMMAND
    }

    fun setReadyToPair() {
        connectionProgress.visibility = View.GONE
        connectionText.text = "Tap to pair with your TV now"
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(Color.parseColor("#d32f2f"))

        currentState = State.READY_TO_PAIR
        currentAction = PAIR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
        }

        val lockView: View = findViewById(R.id.lock)
        lockView.setOnClickListener { authAndSend(LOCK) }
        val unlockView: View = findViewById(R.id.unlock)
        unlockView.setOnClickListener { authAndSend(UNLOCK) }

        connectionProgress = findViewById(R.id.connection_progress)
        connectionIcon = findViewById(R.id.connection_status_icon)
        connectionText = findViewById(R.id.connection_status_text)
        connectionCard = findViewById(R.id.tv_status)
        connectionCard.setOnClickListener {
            if (currentState == State.READY_TO_PAIR) {
                currentAction = PAIR
                startConnectionWithTv()
            }
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val tvServiceId = sharedPreferences.getString(Util.PREF_LOCKIT_RC_SERVICE_ID, Util.LOCKIT_DEFAULT_SERVICE_ID).toString()
        connectedTvName = sharedPreferences.getString(Util.PREF_LOCKIT_TV_NAME, "TV").toString();

        tvService = String.format(Util.LOCKIT_SERVICE_TEMPLATE, tvServiceId)

        if (tvServiceId == Util.LOCKIT_DEFAULT_SERVICE_ID) {
            setReadyToPair()
        } else {
            setReadyToCommand()
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

            if (currentAction == PAIR) {
                val bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val tvServiceId = bufferedReader.readLine()
                Log.d(TAG, "connectToClient: dedicated service id $tvServiceId")
                sharedPreferences.edit().putString(Util.PREF_LOCKIT_RC_SERVICE_ID, tvServiceId).apply()
            }

            bufferedOutputStream.close()
            socket.close()
            Log.d(TAG, "connectToClient: Connection done!")
            runOnUiThread { setReadyToCommand() }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { setReadyToPair() }
        }
    }

    fun resolveAndConnectToClient() {
        val rx2Dnssd = Rx2DnssdBindable(this)
        val browseDisposable = rx2Dnssd.browse(tvService, "local.")
            .compose(rx2Dnssd.resolve())
            .compose(rx2Dnssd.queryIPV4Records())
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe({

                if (currentAction == PAIR) {
                    if (sharedPreferences.getString(Util.PREF_LOCKIT_TV_NAME, "").equals(it.serviceName))
                        return@subscribe
                    sharedPreferences.edit().putString(Util.PREF_LOCKIT_TV_NAME, it.serviceName).apply()
                    connectedTvName = it.serviceName
                }

                Log.d(TAG, "startDiscovery: Registered successfully ${it.inet4Address}")
                connectToClient(it.inet4Address?.hostAddress, it.port)
            }, {
                it.printStackTrace()
            }, {

            })
    }

    fun authAndSend(action: String) {
        if (currentState == State.CONNECTION_ONGOING) {
            showToast("Another request is in progress")
            return
        }

        if (currentState == State.READY_TO_PAIR) {
            showToast("Not paired with your TV")
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
                    runOnUiThread { startConnectionWithTv() }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showToast("Fingerprint enrollment failed")
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

}