package us.aryanmia.buzzr.data

import android.text.TextUtils

data class AlarmStore (
    val id: String?,
    var time: SimpleTime?,
    val members: MutableList<String> = mutableListOf(),
    val awake: MutableList<String> = mutableListOf()
) {

    fun exists() = (!TextUtils.isEmpty(id) && (time?.isValid() ?: false))

    companion object {
        val Empty = AlarmStore(null, null)
    }
}