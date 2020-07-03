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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.druk.rx2dnssd.BonjourService
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
    private lateinit var lockView: TextView
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionIcon: ImageView
    private lateinit var connectionText: TextView
    private lateinit var connectionCard: CardView
    private lateinit var logsList: RecyclerView
    private var tvBonjourService: BonjourService? = null

    private val LOCK = "lock";
    private val UNLOCK = "unlock";
    private val PAIR = "pair";
    private val CONNECT = "connect";

    private lateinit var currentAction: String
    private var connectedTvName: String = "TV"
    private var isLocked = false

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

    fun updateOps() {
        lockView.text = if (isLocked) "Unlock" else "Lock"
        lockView.setCompoundDrawables(if (isLocked) ContextCompat.getDrawable(this, R.drawable.ic_baseline_lock_open_24) else ContextCompat.getDrawable(this, R.drawable.ic_outline_lock_24), null, null, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            lockView.setTextColor(Color.parseColor(if (isLocked) "#66bb6a" else "#ef5350"))
            lockView.compoundDrawableTintList = ColorStateList.valueOf(Color.parseColor(if (isLocked) "#66bb6a" else "#ef5350"))
        }
    }

    fun setReadyToCommand() {
        updateOps()
        connectionProgress.visibility = View.GONE

        connectionCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.cardview_dark_background))

        val text =  "Your $connectedTvName is "
        val status = SpannableString(if (isLocked) "Locked" else "Unlocked")
        status.setSpan(ForegroundColorSpan(Color.parseColor(if (isLocked) "#ef5350" else "#66bbc6")), 0, status.length, 0)
        val spannableStringBuilder = SpannableStringBuilder()
        spannableStringBuilder.append(text)
        spannableStringBuilder.append(status)
        connectionText.setText(spannableStringBuilder, TextView.BufferType.SPANNABLE)

        connectionIcon.visibility = View.VISIBLE
        connectionIcon.setImageResource(if (isLocked) R.drawable.ic_outline_lock_24 else R.drawable.ic_baseline_lock_open_24)

        currentState = State.READY_TO_COMMAND
    }

    fun setReadyToPair() {
        lockView.visibility = View.GONE
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

        lockView = findViewById(R.id.lock)
        lockView.setOnClickListener { authAndSend() }

        logsList = findViewById(R.id.logs_list)
        logsList.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        logsList.adapter = LogsAdapter(this, ArrayList())

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
            currentAction = CONNECT;
            startConnectionWithTv()
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
                (logsList.adapter as LogsAdapter).addLog(AppLog(currentAction, "success"))
                setReadyToCommand()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                (logsList.adapter as LogsAdapter).addLog(AppLog(currentAction, "failure"))
                setReadyToCommand()
            }
        }
    }

    private var isResolvedOnce = false

    fun resolveAndConnectToClient() {
        tvBonjourService?.also { service ->
            Executors.newSingleThreadExecutor().execute {
                connectToClient(service.inet4Address?.hostAddress, service.port)
            }
        } ?: run {
            isResolvedOnce = false;
            val rx2Dnssd = Rx2DnssdBindable(this)
            val browseDisposable = rx2Dnssd.browse(Util.LOCKIT_SERVICE_TEMPLATE.format(Util.LOCKIT_DEFAULT_SERVICE_ID), "local.")
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
                }, {
                })
        }
    }

    fun authAndSend() {
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
                    currentAction = if (isLocked) UNLOCK else LOCK
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