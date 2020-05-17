package us.aryanmia.buzzr.activities

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import us.aryanmia.buzzr.R
import us.aryanmia.buzzr.data.DB
import us.aryanmia.buzzr.ui.Failure
import us.aryanmia.buzzr.ui.Loader
import us.aryanmia.buzzr.ui.needsErrorPrompt

class LoginActivity : AppCompatActivity() {

    private lateinit var gAuthClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    companion object {
        const val RC_SIGN_IN = 1234
        const val NOTIFICATION_CHANNEL_BUZZR = "buzzr_alarm_notification"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        setupBuzzrService()

        val gso = GoogleSignInOptions
            .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        gAuthClient = GoogleSignIn.getClient(this, gso)
        auth = FirebaseAuth.getInstance()
    }

    private fun setupBuzzrService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_BUZZR,
                "Buzzr Alarm Active!",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notifManager = getSystemService(NotificationManager::class.java)
            notifManager?.createNotificationChannel(serviceChannel)
        }

        val alarmServiceIntent = Intent(this, BuzzrAlarmService::class.java)
        startForegroundService(alarmServiceIntent)
    }

    override fun onStart() {
        Loader.show(this, "trying to automagically\nsign you in")
        trySignOn()
        super.onResume()
    }

    /**
     *  Tries to automatically sign-in to the Google account
     */
    private fun trySignOn(useGoogleSignin: Boolean = true) {
        // if a user already does not exist
        // show them the sign-in dialog
        if (!DB.userExists && useGoogleSignin) {
            showSignOnDialog()
            return
        }

        // otherwise try to get their details and login
        DB.getOrCreateUser { alarm, fail ->
            if (needsErrorPrompt(fail)) {
                Loader.hide()
                return@getOrCreateUser
            }

            BuzzrAlarmService.connect(alarm.id)

            runOnUiThread {
                startActivity(when {
                    BuzzrAlarmService.IsAlarmRinging -> {
                        Intent(this, AlarmRingActivity::class.java).apply {
                            putExtra(BuzzrAlarmService.INTENT_DATA_ALARM_ID, BuzzrAlarmService.RingingAlarmID)
                        }
                    }
                    alarm.exists() -> {
                        Intent(this, MainActivity::class.java)
                    }
                    else -> {
                        Intent(this, CreateOrJoinActivity::class.java)
                    }
                })
            }
        }
    }

    /**
     * Shows the Google Sign-in dialog for a result
     */
    private fun showSignOnDialog() {
        val signInIntent = gAuthClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    /**
     *  Handles result from the Google sign-in dialog.
     *  If [user] is null, authentication failed. Else it succeeded.
     */
    private fun googleSignOnResult(user: FirebaseUser?) {
        if (user == null) {
            needsErrorPrompt(Failure.AccountNotFound)
            return
        }

        trySignOn(false)
    }

    /**
     *  Gets the account from the Google sign-in dialog,
     *  and passes it to [googleSignOnResult].
     *  If authentication failed, we pass null to [googleSignOnResult].
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RC_SIGN_IN -> {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val acc = task.getResult(ApiException::class.java)
                    /* Successful sign-in */
                    acc.toFirebaseUser { u -> googleSignOnResult(u) }
                } catch (e: ApiException) {
                    /* Unsuccessful sign-in process */
                    googleSignOnResult(null)
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     *  Converts a [GoogleSignInAccount] into a [FirebaseUser]
     *  and signals [callback] with the converted result (which can be null)
     */
    private fun GoogleSignInAccount?.toFirebaseUser(callback: (FirebaseUser?) -> Unit) {
        if (this == null) {
            callback(null)
            return
        }

        val cred = GoogleAuthProvider.getCredential(this.idToken, null)
        auth.signInWithCredential(cred).addOnCompleteListener { task ->
            callback(if (task.isSuccessful) auth.currentUser else null)
        }
    }

}
