package us.aryanmia.buzzr.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import kotlinx.android.synthetic.main.activity_create_or_join.*
import us.aryanmia.buzzr.R
import us.aryanmia.buzzr.data.DB
import us.aryanmia.buzzr.ui.Loader
import us.aryanmia.buzzr.ui.needsErrorPrompt

class CreateOrJoinActivity : AppCompatActivity() {

    private var backAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_or_join)

        backAllowed = intent.getBooleanExtra(INTENT_OPTION_BACK_ALLOWED, false)

        coj_new_alarm.setOnClickListener {
            MaterialDialog(this).show {
                title(text = "Create a New Alarm Group")
                negativeButton(text = "Cancel")
                positiveButton(text = "Create")
                input(hint = "Make up an identifier for the group.") { _, text ->
                    createAlarmWithID(text.toString())
                }
            }
        }
        coj_join_alarm.setOnClickListener {
            MaterialDialog(this).show {
                title(text = "Join Alarm Group")
                negativeButton(text = "Cancel")
                positiveButton(text = "Join")
                input(hint = "Enter ID of the alarm group.") { _, text ->
                    joinAlarmWithID(text.toString())
                }
            }
        }
    }

    override fun onBackPressed() {
        if (!backAllowed)
            return

        super.onBackPressed()
    }

    private fun createAlarmWithID(alarmID: String) {
        Loader.show(this, "beep..boop..\nmaking $alarmID")

        DB.createAlarm(alarmID) {
            if (this.needsErrorPrompt((it))) {
                Loader.hide()
                return@createAlarm
            }
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun joinAlarmWithID(alarmID: String) {
        Loader.show(this, "beep..boop..\njoining $alarmID")
        DB.joinAlarm(alarmID) {
            if(this.needsErrorPrompt(it)) {
                Loader.hide()
                return@joinAlarm
            }

            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    companion object {
        const val INTENT_OPTION_BACK_ALLOWED = "intent_back_allow"
    }

}
