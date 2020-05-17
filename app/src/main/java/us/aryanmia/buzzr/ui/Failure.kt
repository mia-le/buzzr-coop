package us.aryanmia.buzzr.ui

import us.aryanmia.buzzr.R

/**
 *  Captures all different types of errors that a user might experience
 *  and stores display information for them so we can show it back to the user.
 */
enum class Failure {
    None,
    Unknown,
    AccountNotFound,
    AlarmNotFound,
    AlarmIsRinging,
    AlarmAlreadyExists,
    ConnectionIssue
}

fun Failure.getTitleID(): Int {
    return R.string::class.java.getField("fail_title_${this.name}").get(R.string::class.java) as Int
}
fun Failure.getMessageID(): Int {
    return R.string::class.java.getField("fail_message_${this.name}").get(R.string::class.java) as Int
}