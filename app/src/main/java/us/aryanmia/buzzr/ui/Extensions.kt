package us.aryanmia.buzzr.ui

import android.app.Activity
import android.app.Dialog
import android.text.TextUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.afollestad.materialdialogs.MaterialDialog
import us.aryanmia.buzzr.R

/**
 *  Given a [Failure], shows an error dialog on screen
 *  with the corresponding title and description and returns true.
 *  If the failure code is [Failure.None], does nothing, and
 *  silently returns false.
 *
 *  @return True when there's an error, false otherwise.
 *  @see Failure
 *  @sample [us.aryanmia.buzzr.activities.CreateOrJoinActivity.createAlarmWithID]
 */
fun AppCompatActivity.needsErrorPrompt(status: Failure, callback: () -> Unit = {}): Boolean {
    if (status == Failure.None) {
        return false
    }

    runOnUiThread {
        MaterialDialog(this).show {
            title(text = getString(status.getTitleID()))
            message(text = getString(status.getMessageID()))
            positiveButton(text = getString(R.string.error_user_accept)) {
                callback()
            }
        }
    }

    return true
}

object Loader {

    private var lastDialog: Dialog? = null
    private var lastActivity: Activity? = null

    fun hide() {
        // if there's no associated activity
        // we probably also don't have a dialog being shown
        if (lastActivity == null) {
            lastDialog?.dismiss()
            lastDialog = null
            return
        }

        // if there's an activity and an open dialog
        // close the dialog and delete its reference
        if (lastDialog != null) {
            lastActivity?.runOnUiThread { lastDialog?.dismiss() }
        }
    }

    fun show(act: Activity, message: String = "") {
        // hide any already open dialogs
        hide()

        lastActivity = act
        act.runOnUiThread {
            lastDialog = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            lastDialog!!.apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setContentView(R.layout.dialog_loading)
            }

            if (!TextUtils.isEmpty(message)) {
                lastDialog?.findViewById<TextView>(R.id.dialog_loading_text)?.text = message
            }

            lastDialog!!.show()
        }
    }
}


