package nooblife.lockit.wilock

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.druk.rx2dnssd.BonjourService
import com.github.druk.rx2dnssd.Rx2DnssdBindable
import io.reactivex.schedulers.Schedulers
import java.io.*
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    private val TAG = javaClass.simpleName

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var tvService: String
    private lateinit var lockCommandButton: TextView
    private lateinit var lockCommandCard: CardView
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionIcon: ImageView
    private lateinit var connectionText: TextView
    private lateinit var connectionCard: CardView
    private lateinit var logsList: RecyclerView
    private var tvBonjourService: BonjourService? = null

    private val LOCK = "lock"
    private val UNLOCK = "unlock"
    private val PAIR = "pair"
    private val CONNECT = "connect"

    private lateinit var currentAction: String
    private var connectedTvName: String = "TV"
    private var isLocked = false

    private var currentState: State = State.READY_TO_COMMAND

    enum class State {
        READY_TO_COMMAND, CONNECTION_ONGOING, READY_TO_PAIR, NOT_CONNECTED
    }

    fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    fun startConnectionWithTv(action: String) {
        currentAction = action

        lockCommandCard.visibility = View.GONE
        connectionProgress.visibility = View.VISIBLE
        connectionText.text = "${currentAction[0].toUpperCase()}${currentAction.substring(1)}ing $connectedTvName"
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.yellow_800))

        currentState = State.CONNECTION_ONGOING
        resolveAndConnectToClient()
    }

    fun updateOps() {
        lockCommandCard.visibility = View.VISIBLE
        lockCommandCard.setCardBackgroundColor(
            if (isLocked) Util.giveMeGreen(this) else Util.giveMeRed(this)
        )
        lockCommandButton.text = if (isLocked) "Unlock Now" else "Lock Now"
        lockCommandButton.setCompoundDrawables(
            if (isLocked)
                ContextCompat.getDrawable(this, R.drawable.ic_baseline_lock_open_24)
            else
                ContextCompat.getDrawable(this, R.drawable.ic_outline_lock_24),
            null, null, null)
    }

    fun setReadyToCommand() {
        updateOps()
        connectionProgress.visibility = View.GONE

        connectionCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.cardview_dark_background))

        val preStatusText =  "Your $connectedTvName is "
        val status = SpannableString(
            if (isLocked)
                "Locked"
            else
                "Unlocked"
        )
        status.setSpan(ForegroundColorSpan(if (isLocked) Util.giveMeRed(this) else Util.giveMeGreen(this)), 0, status.length, 0)
        val spannableStringBuilder = SpannableStringBuilder()
        spannableStringBuilder.append(preStatusText)
        spannableStringBuilder.append(status)
        connectionText.setText(spannableStringBuilder, TextView.BufferType.SPANNABLE)

        connectionIcon.visibility = View.VISIBLE
        connectionIcon.setImageResource(
            if (isLocked)
                R.drawable.ic_outline_lock_24
            else
                R.drawable.ic_baseline_lock_open_24
        )

        currentState = State.READY_TO_COMMAND
    }

    fun setReadyToPair() {
        connectionText.text = "Tap to pair with your TV now"
        lockCommandCard.visibility = View.GONE
        connectionProgress.visibility = View.GONE
        connectionIcon.visibility = View.GONE
        connectionCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.yellow_800))

        currentState = State.READY_TO_PAIR
        currentAction = PAIR
    }

    fun setReconnect() {
        connectionText.text = "Tap to try again"
        connectionIcon.visibility = View.GONE
        lockCommandCard.visibility = View.GONE
        connectionProgress.visibility = View.GONE
        connectionCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.yellow_800))

        currentState = State.NOT_CONNECTED
        currentAction = CONNECT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
        }

        // <UI>

        logsList = findViewById(R.id.logs_list)
        logsList.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, true)
        logsList.adapter = LogsAdapter(this, ArrayList())
        logsList.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.HORIZONTAL))

        lockCommandCard = findViewById(R.id.lock_card)
        lockCommandButton = findViewById(R.id.lock)
        lockCommandButton.setOnClickListener { authAndSend() }

        connectionProgress = findViewById(R.id.connection_progress)
        connectionIcon = findViewById(R.id.connection_status_icon)
        connectionText = findViewById(R.id.connection_status_text)
        connectionCard = findViewById(R.id.tv_status)
        connectionCard.setOnClickListener {
            if (currentState == State.READY_TO_PAIR)
                startConnectionWithTv(PAIR)
            else if (currentState == State.NOT_CONNECTED)
                startConnectionWithTv(CONNECT)
        }

        // </UI>

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val tvServiceId = sharedPreferences.getString(Util.PREF_LOCKIT_RC_SERVICE_ID, Util.LOCKIT_DEFAULT_SERVICE_ID).toString()
        connectedTvName = sharedPreferences.getString(Util.PREF_LOCKIT_TV_NAME, "TV").toString();

        tvService = String.format(Util.LOCKIT_SERVICE_TEMPLATE, tvServiceId)

        if (tvServiceId == Util.LOCKIT_DEFAULT_SERVICE_ID) {
            setReadyToPair()
        } else {
            startConnectionWithTv(CONNECT)
        }
    }

    fun connectToClient(host: String?, port: Int) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            Log.d(TAG, "connectToClient: Connection to Server Success")
            val bufferedOutputStream = BufferedOutputStream(socket.getOutputStream())
            val printWriter = PrintWriter(bufferedOutputStream)
            printWriter.println(currentAction)
            printWriter.flush()
            Log.d(TAG, "connectToClient: Data written to buffer")

            val bufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val serverSays = bufferedReader.readLine()

            when (currentAction) {
                PAIR -> {
                    Log.d(TAG, "connectToClient: dedicated service id $serverSays")
                    sharedPreferences.edit().putString(Util.PREF_LOCKIT_RC_SERVICE_ID, serverSays).apply()
                }
                CONNECT -> {
                    Log.d(TAG, "connectToClient: success");
                    isLocked = serverSays.toBoolean()
                }
                LOCK -> isLocked = true
                UNLOCK -> isLocked = false
            }

            bufferedReader.close()
            bufferedOutputStream.close()
            socket.close()
            Log.d(TAG, "connectToClient: Connection done!")
            runOnUiThread {
                (logsList.adapter as LogsAdapter).addLog(AppLog(currentAction, "success", true))
                setReadyToCommand()
            }
        } catch (ste: SocketTimeoutException) {
            Log.e(TAG, "connectToClient: client IP possibly changed OR Service isn't running")
            ste.printStackTrace()
            tvBonjourService = null
            resolveAndConnectToClient()
        } catch (ce: ConnectException) {
            Log.e(TAG, "connectToClient: client IP possibly changed OR Service isn't running")
            tvBonjourService = null
            resolveAndConnectToClient()
        } catch (ioe: IOException) {
            ioe.printStackTrace()
            runOnUiThread {
                (logsList.adapter as LogsAdapter).addLog(AppLog(currentAction, "failure", true))
                setReadyToCommand()
            }
        }
    }

    fun resolveAndConnectToClient() {
        tvBonjourService?.also { service ->
            Executors.newSingleThreadExecutor().execute {
                connectToClient(service.inet4Address?.hostAddress, service.port)
            }
        } ?: run {
            var isResolvedOnce = false;
            val rx2Dnssd = Rx2DnssdBindable(this)
            rx2Dnssd.browse(Util.LOCKIT_SERVICE_TEMPLATE.format(Util.LOCKIT_DEFAULT_SERVICE_ID), "local.")
                .timeout(5000, TimeUnit.MILLISECONDS)
                .compose(rx2Dnssd.resolve())
                .compose(rx2Dnssd.queryIPV4Records())
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe({

                    if (isResolvedOnce)
                        return@subscribe

                    isResolvedOnce = true

                    if (currentAction == PAIR) {
                        sharedPreferences.edit().putString(Util.PREF_LOCKIT_TV_NAME, it.serviceName).apply()
                        connectedTvName = it.serviceName
                    }

                    if (tvBonjourService == null) {
                        tvBonjourService = it
                    }

                    Log.d(TAG, "startDiscovery: Registered successfully ${it.inet4Address}")
                    connectToClient(it.inet4Address?.hostAddress, it.port)
                }, {
                    it.printStackTrace()
                    runOnUiThread {
                        if (currentAction == PAIR) {
                            showToast("Check if LockIt is running in your TV and it's in the same WiFi network")
                            setReadyToPair()
                        } else {
                            showToast("Check if your TV is connected to this WiFi network")
                            setReconnect()
                        }
                    }
                }, {
                })
        }
    }

    fun authAndSend() {
        if (currentState == State.CONNECTION_ONGOING) {
            showToast("Another request is in progress")
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setNegativeButtonText("Cancel")
            .setTitle(getString(R.string.app_name))
            .setDescription("Please verify it's you")
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
                    runOnUiThread { startConnectionWithTv(if (isLocked) UNLOCK else LOCK) }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showToast("Fingerprint enrollment failed")
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

}