package us.aryanmia.buzzr.data

import android.text.TextUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import us.aryanmia.buzzr.activities.BuzzrAlarmService
import us.aryanmia.buzzr.ui.Failure
import com.google.firebase.firestore.FirebaseFirestoreException.Code as DBError

/*
        TYPES OF CALLBACKS FOR DATABASE METHODS
 */
typealias AlarmCallback = (alarm: AlarmStore, status: Failure) -> Unit
typealias FailureCallback = (status: Failure) -> Unit
typealias DocumentChangeListener = (doc: DocumentSnapshot?, err: FirebaseFirestoreException?) -> Unit

/*
        DATABASE DESIGN

    [alarms] collection has many documents.
    each document has a unique ID which is randomly generated
    and contains the following fields:
        ~> members: listOf<String>     // who all are members
        ~> awake: listOf<String>     // members who are awake
        ~> time: DateTime              // when the alarm will ring
        ~> ringing: Boolean              // is the alarm currently ringing

    [users] collection has many documents.
    each document has a unique ID which is the users email (when they login with gmail)
    and contains the following fields:
        ~> alarmID: String             // ID of the alarm associated with this user
 */
object DB {

    val userExists = !TextUtils.isEmpty(auth.currentUser?.email)

    const val ALARM_AWAKE = "awake"
    const val ALARM_MEMBERS = "members"
    const val ALARM_RINGING = "ringing"
    const val ALARM_ALL_AWAKE = "allAwake"
    const val ALARM_TIME = "time"

    const val USER_ALARM = "alarm"

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private val firestore: FirebaseFirestore
        get() = Firebase.firestore

    private val users
        get() = firestore.collection("users")

    private val alarms
        get() = firestore.collection("alarms")

    fun listenToAlarmChange(alarmID: String, listener: DocumentChangeListener): ListenerRegistration {
        return alarms.document(alarmID).addSnapshotListener(listener)
    }

    /**
     *  Get the details of the alarm associated with the
     *  currently logged in user.
     */
    fun getOrCreateUser(callback: AlarmCallback) {
        val userEmail = auth.currentUser?.email!!
        if (TextUtils.isEmpty(userEmail)) {
            callback(AlarmStore.Empty, Failure.AccountNotFound)
            return
        }

        firestore.runTransaction { store ->
            /**
             * @throws FirebaseFirestoreException.Code.INVALID_ARGUMENT
             */
            val userDoc = users.document(userEmail)
            val user = store.get(userDoc)

            // if the user has never signed-in before
            // an entry for it won't exist in our database
            if (!user.exists()) {
                store.set(userDoc, hashMapOf(
                    USER_ALARM to null
                ))

                BuzzrAlarmService.connect()
                callback(AlarmStore.Empty, Failure.None)
                return@runTransaction
            }

            val alarmID = user.get("alarm") as String?
            // if the alarmID is empty, the user has not registered
            // with an alarm yet
            if (TextUtils.isEmpty(alarmID)) {
                BuzzrAlarmService.connect()
                callback(AlarmStore.Empty, Failure.None)
                return@runTransaction
            }

            /**
             * @throws FirebaseFirestoreException.Code.INVALID_ARGUMENT
             */
            val currentAlarm = store.get(alarms.document(alarmID!!))
            if (currentAlarm.exists()) {
                BuzzrAlarmService.connect(alarmID)
                callback(
                    AlarmStore(
                        id = currentAlarm.id,
                        time = SimpleTime.fromString(currentAlarm.get(ALARM_TIME) as String?),
                        members = currentAlarm.get(ALARM_MEMBERS).toMutableList(),
                        awake = currentAlarm.get(ALARM_AWAKE).toMutableList()
                ), Failure.None)
            }
            // if the alarmID is not null, but the alarm entry does not exist
            // something weird has happened, so we remove the link to the alarm for the user
            else {
                store.update(users.document(userEmail), USER_ALARM, null)
                BuzzrAlarmService.connect(alarmID)
                callback(AlarmStore.Empty, Failure.None)
            }
        }.addOnFailureListener { exp ->
            exp.toFailureCode()?.let { code ->
                callback(AlarmStore.Empty, code)
            }
        }
    }

    /**
     *  Create an alarm with ID [alarmID].
     *  If the user is already registered with an alarm,
     *  remove the user from that.
     */
    fun createAlarm(alarmID: String, callback: FailureCallback) {
        if (TextUtils.isEmpty(alarmID)) {
            callback(Failure.AlarmNotFound)
            return
        }

        val userEmail = auth.currentUser!!.email!!
        firestore.runTransaction { store ->
            val user = store.get(users.document(userEmail))
            val newAlarm = store.get(alarms.document(alarmID))


            // if an alarm with the same name exists, cannot create it
            if (newAlarm.exists()) {
                callback(Failure.AlarmAlreadyExists)
                return@runTransaction
            }

            // remove user from existing alarm
            val prevAlarmID = user.get(USER_ALARM) as String?
            if (!TextUtils.isEmpty(prevAlarmID)) {
                val prevAlarm = store.get(alarms.document(prevAlarmID!!))

                // if the previous alarm exists, remove the user from it
                if (prevAlarm.exists()) {
                    val prevAlarmMembers = prevAlarm.get(ALARM_MEMBERS).toMutableList()
                    prevAlarmMembers.removeUnique(userEmail)

                    // if there aren't any members left, delete the alarm
                    when (prevAlarmMembers.size) {
                        0 -> store.delete(alarms.document(prevAlarmID))
                        else -> store.update(alarms.document(prevAlarmID), ALARM_MEMBERS, prevAlarmMembers)
                    }
                }
            }

            // create a new alarm with the relevant details
            store.set(alarms.document(alarmID), hashMapOf(
                ALARM_TIME to SimpleTime.Default.toString(),
                ALARM_MEMBERS to mutableListOf(userEmail),
                ALARM_AWAKE to mutableListOf<String>(),
                ALARM_RINGING to false,
                ALARM_ALL_AWAKE to false
            ))

            store.update(users.document(userEmail), USER_ALARM, alarmID)
            BuzzrAlarmService.connect(alarmID)
            callback(Failure.None)
        }.addOnFailureListener { it.toFailureCode()?.let(callback) }
    }

    /**
     *  Given an alarm ID [newAlarmID], if there exists an alarm
     *  add the current user as a member
     */
    fun joinAlarm(newAlarmID: String, callback: FailureCallback) {

        // if the given alarm ID is empty,
        // any alarm can't exist
        if (TextUtils.isEmpty(newAlarmID)) {
            callback(Failure.AlarmNotFound)
            return
        }

        firestore.runTransaction { t ->
            val userEmail = auth.currentUser?.email!!
            val userDoc = users.document(userEmail)
            val user = t.get(userDoc)

            val newAlarmDoc = alarms.document(newAlarmID)
            val newAlarm = t.get(newAlarmDoc)

            if (!newAlarm.exists()) {
                callback(Failure.AlarmNotFound)
                return@runTransaction
            }

            if (newAlarm.get(ALARM_RINGING) as Boolean? == true) {
                callback(Failure.AlarmIsRinging)
                return@runTransaction
            }

            val newAlarmMembers = newAlarm.get(ALARM_MEMBERS).toMutableList()
            newAlarmMembers.addUnique(userEmail)

            // If there's an existing alarm attached to the user, remove the user from it.
            val prevAlarmID = user.get(USER_ALARM) as String?
            if (!TextUtils.isEmpty(prevAlarmID)) {
                val prevAlarmDoc = alarms.document(prevAlarmID!!)
                val prevAlarm = t.get(prevAlarmDoc)

                val previousAlarmMembers = prevAlarm.get(ALARM_MEMBERS).toMutableList()
                previousAlarmMembers.removeUnique(userEmail)

                when (previousAlarmMembers.size) {
                    0 -> t.delete(prevAlarmDoc)
                    else -> t.update(prevAlarmDoc, ALARM_MEMBERS, previousAlarmMembers)
                }
            }

            // Add the user to the new alarm
            t.update(newAlarmDoc, ALARM_MEMBERS, newAlarmMembers)
            t.update(userDoc, USER_ALARM, newAlarmID)
            BuzzrAlarmService.connect(newAlarmID)
            callback(Failure.None)
        }.addOnFailureListener {
            it.toFailureCode()?.let(callback)
        }

    }

    /**
     *  Given an alarm ID [alarmID], remove's the user from that alarm
     *  if the user is actually a part of it.
     */
    fun leaveAlarm(alarmID: String?, callback: FailureCallback) {
        if (TextUtils.isEmpty(alarmID)) {
            callback(Failure.AlarmNotFound)
            return
        }


        firestore.runTransaction { t ->
            val alarm = t.get(alarms.document(alarmID!!))

            if (!alarm.exists()) {
                callback(Failure.AlarmNotFound)
                return@runTransaction
            }

            if (alarm.get(ALARM_RINGING) as Boolean? == true) {
                callback(Failure.AlarmIsRinging)
                return@runTransaction
            }

            val members = alarm.get(ALARM_MEMBERS).toMutableList()
            members.removeUnique(auth.currentUser?.email)
            if (members.size == 0) {
                t.delete(alarms.document(alarmID))
            } else {
                t.update(alarms.document(alarmID), ALARM_MEMBERS, members)
            }

            t.update(users.document(auth.currentUser?.email!!), USER_ALARM, null)
            BuzzrAlarmService.connect()
            callback(Failure.None)
        }.addOnFailureListener { it.toFailureCode()?.let(callback) }
    }

    /**
     *  Given an alarm ID [alarmID], update the time of the corresponding alarm
     *  to [newTime] only if an alarm with the ID [alarmID] exists.
     */
    fun updateAlarmTime(alarmID: String?, newTime: SimpleTime?, callback: FailureCallback) {
        if (TextUtils.isEmpty(alarmID)) {
            callback(Failure.AlarmNotFound)
            return
        }

        if (newTime == null) {
            callback(Failure.Unknown)
            return
        }

        firestore.runTransaction { t ->
            val alarm = t.get(alarms.document(alarmID!!))

            if (!alarm.exists()) {
                callback(Failure.AlarmNotFound)
                return@runTransaction
            }

            if (alarm.get(ALARM_RINGING) as Boolean) {
                callback(Failure.AlarmIsRinging)
                return@runTransaction
            }

            t.update(alarms.document(alarmID), ALARM_TIME, newTime.toString())
            BuzzrAlarmService.connect(alarmID)
            callback(Failure.None)
        }.addOnFailureListener { it.toFailureCode()?.let(callback) }
    }

    /**
     *  Given an alarm with ID [alarmID], adds the users email to the
     *  list of people who have woken up.
     */
    fun setAwake(alarmID: String?, callback: FailureCallback) {
        if (TextUtils.isEmpty(alarmID)) {
            callback(Failure.AlarmNotFound)
            return
        }

        firestore.runTransaction { db ->
            val alarmDoc = alarms.document(alarmID!!)
            val alarmData = db.get(alarmDoc)
            val alarmMembers = alarmData.get(ALARM_MEMBERS).toMutableList()
            val awakeMembers = alarmData.get(ALARM_AWAKE).toMutableList()
            awakeMembers.addUnique(auth.currentUser?.email!!)

            // this means we are the last ones to wake up
            if (alarmMembers.size == awakeMembers.size) {
                BuzzrAlarmService.LastAwake = true
                db.update(alarmDoc, ALARM_ALL_AWAKE, true)
            }

            db.update(alarmDoc, ALARM_RINGING, true)
            db.update(alarmDoc, ALARM_AWAKE, awakeMembers)
            callback(Failure.None)
        }.addOnFailureListener { it.toFailureCode()?.let(callback) }
    }

    /**
     *  Resets the alarm to its normal condition i.e.
     *  everyone asleep and alarm not ringing
     */
    fun resetAlarmPeople(alarmID: String?, callback: FailureCallback) {
        if (TextUtils.isEmpty(alarmID)) {
            callback(Failure.AccountNotFound)
            return
        }

        firestore.runTransaction { db ->
            val alarmDoc = alarms.document(alarmID!!)
            db.update(alarmDoc, ALARM_RINGING, false)
            db.update(alarmDoc, ALARM_ALL_AWAKE, false)
            db.update(alarmDoc, ALARM_AWAKE, mutableListOf<String>())
        }.addOnFailureListener { it.toFailureCode()?.let(callback) }
    }

    fun triggerNow(alarmID: String?) {

    }
}

fun Exception.toFailureCode(): Failure? {
    if (this is FirebaseFirestoreException) {
        return when (this.code) {
            DBError.UNAVAILABLE -> Failure.ConnectionIssue
            DBError.INVALID_ARGUMENT -> Failure.AccountNotFound
            DBError.UNKNOWN -> Failure.Unknown
            else -> null
        }
    }

    return null
}

/**
 *  Cast an object into a mutable list.
 *  If the cast fails, return an empty mutable list.
 */
fun Any?.toMutableList(): MutableList<String> {
    return (this as MutableList<String>?) ?: mutableListOf()
}

/**
 *  Given a list, add an element to it if it doesn't already exist.
 *  If [this] is empty, then return an list only containing [item]
 */
fun MutableList<String>.addUnique(item: String?) {
    if (TextUtils.isEmpty(item))
        return

    if (!this.contains(item)) {
        this.add(item!!)
    }
}

/**
 *  Given a list, remove an element from it if it does exist.
 *  If [this] is empty, then return an empty list.
 */
fun MutableList<String>.removeUnique(item: String?) {
    if (TextUtils.isEmpty(item))
        return

    this.removeAll { x -> x == item!! }
}