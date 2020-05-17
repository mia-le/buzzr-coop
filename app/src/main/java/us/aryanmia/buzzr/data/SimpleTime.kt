package us.aryanmia.buzzr.data

import java.lang.Integer.parseInt
import java.util.*
import kotlin.math.floor

/**
 * [hour]:[minute] are stored in 24-hour format
 * with the timezone being GMT+0
 */
data class SimpleTime private constructor(
    val hour: Int,
    val minute: Int
) {

    fun isValid(): Boolean {
        return (hour >= 0) && (hour <= 23) && (minute >= 0) && (minute <= 59)
    }

    private fun getMinuteOffset(): Int {
        val localTime = Calendar.getInstance()
        return (localTime.timeZone.getOffset(localTime.time.time)) / (1000 * 60)
    }

    fun ampmSuffix(): String {
        return if (hour < 12) "AM" else "PM"
    }
    fun get12Hour(): String {
        return "${(hour % 12).timePadZero()}:${minute.timePadZero()}"
    }

    fun toLocal(): Calendar {
        val utcTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }

        val localTime = utcTime.apply {
            add(Calendar.MINUTE, getMinuteOffset())
        }

        val currentTime = Calendar.getInstance()
        val nextDayTime = Calendar.getInstance().apply {
            this.add(Calendar.HOUR, 24)
        }
        if (localTime.before(currentTime)) {
            localTime.add(Calendar.HOUR, 24)
        } else if (localTime.after(nextDayTime)) {
            localTime.add(Calendar.HOUR, -24)
        }

        return localTime
    }

    private fun getHourMinuteOffset(): Pair<Int, Int> {
        val minuteOffset = getMinuteOffset()
        val hours = floor(minuteOffset / 60f).toInt()
        val minutes = (minuteOffset - (hours * 60))
        return Pair(hours, minutes)
    }

    fun timeToNextAlarm(): String {
        val alarmRingTime = toLocal()
        val currentTime = Calendar.getInstance()

        val diffTotalMinutes = (alarmRingTime.timeInMillis - currentTime.timeInMillis) / (1000 * 60)
        val diffOnlyHours = (diffTotalMinutes / 60).toInt()
        val diffOnlyMins = diffTotalMinutes - (diffOnlyHours * 60)

        val sb = StringBuilder()
        if (diffOnlyHours > 0) {
            sb
                .append(diffOnlyHours)
                .append(" hrs")
        }
        if (diffOnlyMins > 0) {
            sb
                .append(if (diffOnlyHours > 0) " & " else "")
                .append(diffOnlyMins)
                .append(" min")
                .append(if (diffOnlyMins > 1) "s" else "")
        }

        if (sb.toString().isEmpty()) {
            sb.append("less than a minute")
        }

        return sb.toString()
    }

    override fun toString(): String {
        return "${hour.timePadZero()}:${minute.timePadZero()}"
    }

    fun getLocalTime(): Pair<String, String> {
        val localTime = toLocal()
        var hour12 = localTime.get(Calendar.HOUR).timePadZero()
        val minute = localTime.get(Calendar.MINUTE).timePadZero()
        val ampm = if (localTime.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"

        hour12 = if (hour12 == "00") "12" else hour12

        return Pair("$hour12:$minute", ampm)
    }

    fun getLocalOffset(): String {
        val hmo = getHourMinuteOffset()
        return "${if (hmo.first >= 0) "+" else ""}${hmo.first.timePadZero()}:${hmo.second.timePadZero()}"
    }

    override fun equals(other: Any?): Boolean {
        return (other is SimpleTime &&
                other.hour == this.hour &&
                other.minute == this.minute)
    }

    override fun hashCode(): Int {
        var result = hour
        result = 31 * result + minute
        return result
    }

    companion object {

        fun fromString(str: String?): SimpleTime {
            return try {
                val vals = str!!.split(":")
                SimpleTime(parseInt(vals[0]), parseInt(vals[1]))
            } catch (e: Exception) {
                Default
            }
        }

        fun fromLocal(time: Calendar): SimpleTime {
            // UTC offset in minutes (eg: UTC+5:30 --> 330)
            val utcOffsetMins = (time.timeZone.getOffset(time.time.time)) / (1000 * 60)
            // go back by the offset to get the UTC time
            time.add(Calendar.MINUTE, -utcOffsetMins)

            return SimpleTime(time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE))
        }

        val Default = SimpleTime(0,0)

    }

}

fun Int.timePadZero(): String {
    return "${if (this <= 9) "0" else ""}$this"
}