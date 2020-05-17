package us.aryanmia.buzzr.activities

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import us.aryanmia.buzzr.R
import us.aryanmia.buzzr.data.DB
import us.aryanmia.buzzr.data.SimpleTime
import us.aryanmia.buzzr.ui.Loader

class BuzzrAlarmService: Service(), LifecycleObserver {

    companion object {
        var Instance: BuzzrAlarmService? = null
            private set

        fun connect(alarmID: String? = null) = Instance?.setAlarmConnection(alarmID)
        fun toggleAlarmSound(play: Boolean? = null) {
            when {
                (play == null) -> Instance?.toggleAlarmSound()
                play -> Instance?.playAlarmSound()
                else -> Instance?.stopAlarmSound()
            }
        }

        var LastAwake = false
        var RingingAlarmID: String? = null
        var IsAlarmRinging: Boolean = false

        const val DEFAULT_NOTIFICATION_ID = 4
        const val ALARM_REQUEST_CODE = 24
        const val ACTION_ALARM_STOPPED = "buzzr.alarm.stopped"
        const val INTENT_DATA_ALARM_ID = "alarmID"
        const val INTENT_DATA_CALLING_ACTIVITY = "callingActivity"
    }

    private var uiThreadHandler: Handler? = null

    /**
     *  The pending intent which will be shown
     *  when the next alarm rings.
     */
    private var alarmIntent: PendingIntent? = null

    /**
     *  Is our app running in the foreground or not i.e.
     *  is the user interacting with it currently?
     */
    private var runningInBackground = false

    /**
     *  If not null, can be used to stop the
     *  currently playing alarm sound.
     */
    private var alarmPlayer: MediaPlayer? = null

    /** Listens to changes in the FireBase document
     *      /alarms/{userAlarmID}
     */
    private var docChangeListener: ListenerRegistration? = null

    /**
     *  Caches the time of the alarm that was last set
     *  such that if the new alarm time is the same, an
     *  update for the server will not be generated.
     */
    private var lastKnownTime: SimpleTime? = null

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        runningInBackground = false
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        runningInBackground = true
        Loader.hide()
    }

    /**
     *  When the app is started, generate a foreground service with a sticky
     *  notification indicating that a service is running in the background.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmID = intent?.getStringExtra(INTENT_DATA_ALARM_ID)
        if (!alarmID.isNullOrBlank()) {
            startAlarm()
            this.startActivity(Intent(this, AlarmRingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(INTENT_DATA_ALARM_ID, alarmID)
            })
            showNotification(getString(R.string.alarm_notification))
        } else {
            showNotification()
        }

        return START_STICKY
    }
    override fun onCreate() {
        Instance = this
        uiThreadHandler = Handler(Looper.getMainLooper())
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        super.onCreate()
    }
    override fun onDestroy() {
        hideNotification()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     *  Updates the main sticky notification for the app
     *  with a [message] and an optional [badgeCount] for home screen badges.
     */
    private fun showNotification(message: String? = null) {
        val notifIntent = Intent(
            this, LoginActivity::class.java
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val onClickIntent = PendingIntent.getActivity(
            this, 0, notifIntent, 0
        )

        val notif = NotificationCompat.Builder(this, LoginActivity.NOTIFICATION_CHANNEL_BUZZR).apply {
            setContentTitle(getString(R.string.service_notification_title))
            setContentText(message ?: getString(R.string.service_notification_default_message))
            setSmallIcon(R.drawable.loader_google_large)

            if (IsAlarmRinging) {
                setFullScreenIntent(onClickIntent, true)
            } else {
                setContentIntent(onClickIntent)
            }

        }.build()

        startForeground(DEFAULT_NOTIFICATION_ID, notif)
    }
    private fun hideNotification() {
        stopForeground(true)
    }

    /**
     *  Ensures that after any changes to the alarm associated with the user,
     *  we are listening to changes on the correct alarm i.e. with ID [alarmID]
     */
    private fun setAlarmConnection(alarmID: String?) {
        docChangeListener?.remove()
        if (TextUtils.isEmpty(alarmID))
            return

        docChangeListener = DB.listenToAlarmChange(alarmID!!) { d, e -> alarmStateMachine(d,e) }
    }
    /**
     *  Handles what to do when we detect changes in the document snapshot
     *  [doc] of the associated alarm such as setting or removing an alarm locally
     */
    private fun alarmStateMachine(doc: DocumentSnapshot?, err: FirebaseFirestoreException?) {
        RingingAlarmID = doc?.id
        val time = SimpleTime.fromString(doc?.get(DB.ALARM_TIME) as String?)
        val allAwake = doc?.get(DB.ALARM_ALL_AWAKE) as Boolean? == true

        /*
                CHANGE DETECTED     |       ACTION TAKEN
            ----------------------------------------------
            time changed            -   set alarm locally
            everyone is awake       -   stop the alarm
            you were awake last     -   reset the alarm

         */

        // if the time value is changed, update the alarm
        // and send out the new notification
        if (!(time == lastKnownTime || IsAlarmRinging)) {
            lastKnownTime = time
            setAlarmLocally(time)
        }

        if (allAwake) {
            stopAlarm()
        }

        if (LastAwake) {
            DB.resetAlarmPeople(RingingAlarmID) {
                LastAwake = false
            }
        }
    }


    private fun setAlarmLocally(time: SimpleTime) {
        val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmIntent = PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, this::class.java).apply {
                putExtra(INTENT_DATA_ALARM_ID, RingingAlarmID)
            },
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        // set an alarm at the given local time
        alarmService.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            time.toLocal().timeInMillis,
            alarmIntent
        )

        val locTime = time.getLocalTime()
        // send out a notification
        showNotification(
            resources.getString(
                R.string.wakeup_notification,
                RingingAlarmID,
                "${locTime.first} ${locTime.second}"
            )
        )
    }
    fun startAlarm() {
        IsAlarmRinging = true
        LastAwake = false
        playAlarmSound()
    }
    fun stopAlarm() {
        stopAlarmSound()
        sendBroadcast(Intent(ACTION_ALARM_STOPPED))
        IsAlarmRinging = false
//        lastKnownTime?.let {
//            setAlarmLocally(it)
//        }
    }

    private fun playAlarmSound() {
        stopAlarmSound()
        alarmPlayer = MediaPlayer().apply {
            isLooping = true
            setDataSource(applicationContext, Uri.parse("android.resource://us.aryanmia.buzzr/${R.raw.elevator}"))
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
            setVolume(1.0f,1.0f)
        }
        alarmPlayer?.prepare()
        alarmPlayer?.start()
    }
    private fun stopAlarmSound() {
        alarmPlayer?.let {
            it.stop()
            it.release()
        }
        alarmPlayer = null
    }
    private fun toggleAlarmSound() {
        if (alarmPlayer?.isPlaying == true) {
            stopAlarmSound()
        } else {
            playAlarmSound()
        }
    }

}