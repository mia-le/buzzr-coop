package us.aryanmia.buzzr.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.datetime.timePicker
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import us.aryanmia.buzzr.R
import us.aryanmia.buzzr.data.AlarmStore
import us.aryanmia.buzzr.data.DB
import us.aryanmia.buzzr.data.SimpleTime
import us.aryanmia.buzzr.ui.Failure
import us.aryanmia.buzzr.ui.Loader
import us.aryanmia.buzzr.ui.MemberRVA
import us.aryanmia.buzzr.ui.needsErrorPrompt
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var membersListAdapter: MemberRVA
    private lateinit var membersListViewManager: RecyclerView.LayoutManager

    private var alarm: AlarmStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        /* Setup recycler view to show members of the current alarm  */
        membersListAdapter = MemberRVA()
        membersListViewManager = LinearLayoutManager(this)

        /* Click the time shown to change time */
        main_alarm_time.setOnClickListener {
            MaterialDialog(this).show {
                title(text = "When do you want to wake up? (in your timezone))")
                timePicker(show24HoursView = false) { _, time ->
                    updateTime(time)
                }
            }
        }

        membersListAdapter = MemberRVA()
        main_alarm_members.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = membersListAdapter
        }

        /* Click the FAB button to go to change alarm screen */
        main_action_button.setOnClickListener {
            networkUpdateUI()
        }
    }
    override fun onResume() {
        networkUpdateUI()
        super.onResume()
    }
    override fun onBackPressed() {
        return
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.main_menu_leave -> leaveAlarmGroup()
            R.id.main_menu_change_alarm -> goToCreateOrJoinScreen(true)
            R.id.main_menu_fake_alarm -> {
                BuzzrAlarmService.toggleAlarmSound(
                    if (item.isCheckable) null else item.isChecked
                )

                if (item.isCheckable)
                    item.isChecked = !item.isChecked
            }
            R.id.main_menu_reset_alarm -> {
                alarm?.id?.let {
                    DB.resetAlarmPeople(it) { f ->
                        runOnUiThread { needsErrorPrompt(f) }
                    }
                }
            }
        }

        return true
    }

    private fun updateTime(localTime: Calendar) {
        Loader.show(this@MainActivity, "beaming up\nthe new time..")
        val newTime = SimpleTime.fromLocal(localTime)

        DB.updateAlarmTime(alarm?.id, newTime) { fail ->
            Loader.hide()
            if (needsErrorPrompt(fail)) {
                return@updateAlarmTime
            }

            runOnUiThread {
                alarm?.time = newTime
                localUpdateUI()
            }
        }
    }
    private fun goToCreateOrJoinScreen(allowBackButton: Boolean = false) {
        val intent = Intent(this, CreateOrJoinActivity::class.java)
        intent.putExtra(CreateOrJoinActivity.INTENT_OPTION_BACK_ALLOWED, allowBackButton)
        startActivity(intent)
    }
    private fun leaveAlarmGroup() {
        MaterialDialog(this).show {
            title(text = "Leaving Group!")
            message(text = "If you are sure that you want to leave the current alarm group, press the OUT! button below.")
            negativeButton(text = "Cancel")
            positiveButton(text = "OUT!") {

                DB.leaveAlarm(alarm?.id) {
                    if (!needsErrorPrompt(it)){
                        runOnUiThread {
                            goToCreateOrJoinScreen(false)
                        }
                    }
                }

            }
        }
    }

    private fun localUpdateUI() {
        alarm?.let {
            membersListAdapter.setItems(alarm!!.members, alarm!!.awake)
            val alm = alarm!!

            // name of the alarm
            toolbar_layout.title = "${alm.id}"
            // time till the next alarm (eg: 7 hrs & 30 mins)
            main_alarm_members_title.text = "${alm.time?.timeToNextAlarm()}"

            // gets local time along with suffix (AM/PM)
            val localTimeStuff = alm.time?.getLocalTime()
            main_alarm_time.text = localTimeStuff?.first
            main_alarm_ampm.text = localTimeStuff?.second
            main_alarm_above_main_time.text = "GMT${alm.time?.getLocalOffset()}"

            // display the UTC time below
            main_alarm_time_user.text = "${alm.time?.get12Hour()}${alm.time?.ampmSuffix()?.toLowerCase()} GMT"
//            // utc time in 12 hour format (eg: 11:48)
//            main_alarm_time.text = "${alm.time?.get12Hour()}"
//            // utc time suffix (eg: AM)
//            main_alarm_ampm.text = "${alm.time?.ampmSuffix()}"
//            // local time with suffix (eg: 4:28 PM)
//            main_alarm_time_user.text = "${alm.time?.getLocalTime()} (UTC${alm.time?.getLocalOffset()})"
        }
    }
    private fun networkUpdateUI(alsoUpdate: Boolean = false) {
        Loader.show(this, "fetching the deets")

        DB.getOrCreateUser { alarm, _ ->
            if (!alarm.exists()) {
                runOnUiThread {
                    needsErrorPrompt(Failure.Unknown) {
                        finishAffinity()
                    }
                }
                return@getOrCreateUser
            }

            this.alarm = alarm
            runOnUiThread {
                localUpdateUI()
            }
            Loader.hide()
        }
    }
}
