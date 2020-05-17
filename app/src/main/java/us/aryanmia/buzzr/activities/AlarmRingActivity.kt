package us.aryanmia.buzzr.activities

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_alarm_ring.*
import us.aryanmia.buzzr.R
import us.aryanmia.buzzr.data.DB
import us.aryanmia.buzzr.ui.needsErrorPrompt

class AlarmRingActivity : AppCompatActivity(), View.OnClickListener {

    private var awakeClicked = false
    private var emergencyStopClicked = false
    private lateinit var alarmRingingFor: String
    private var alarmStopBroadcast: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_ring)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        with(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager) {
            requestDismissKeyguard(this@AlarmRingActivity, null)
        }

        // start playing a sound for the alarm
//        BuzzrAlarmService.Instance?.startAlarm()
        alarmRingingFor = intent.getStringExtra(BuzzrAlarmService.INTENT_DATA_ALARM_ID) ?: ""
        if (TextUtils.isEmpty(alarmRingingFor)) {
            BuzzrAlarmService.Instance?.stopAlarm()
            goToMainScreen()
            return
        }

        // listen to when everyone is awake
        alarmStopBroadcast = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                goToMainScreen()
            }
        }
        val stopFilter = IntentFilter(BuzzrAlarmService.ACTION_ALARM_STOPPED)
        registerReceiver(alarmStopBroadcast, stopFilter)

        ring_emergency_stop.setOnClickListener(this)
        ring_awake.setOnClickListener(this)
    }

    private fun goToMainScreen() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    override fun onDestroy() {
        alarmStopBroadcast?.let { unregisterReceiver(it) }
        super.onDestroy()
    }


    override fun onClick(v: View?) {
        if (v == ring_emergency_stop) {
            if (emergencyStopClicked)
                return

            BuzzrAlarmService.Instance?.stopAlarm()
            ring_awake.performClick()
            ring_emergency_stop.text = getString(R.string.ring_emergency_stop_on)
        } else if(v == ring_awake) {
            if (awakeClicked)
                return

            awakeClicked = true

            DB.setAwake(alarmRingingFor) { fail ->
                // if there's an error, stop the alarm lol
                // bc it'd be unfair for no internet to break the service
                if (needsErrorPrompt(fail)) {
                    BuzzrAlarmService.Instance?.stopAlarm()
                    runOnUiThread {
                        ring_notify.text = getString(R.string.ring_awake_error)
                    }
                    return@setAwake
                }

                runOnUiThread {
                    ring_awake.text = getString(R.string.ring_awake_declare)
                    ring_notify.visibility = View.VISIBLE
                }
            }
        }
    }
    override fun onBackPressed() {
        return
    }
}
