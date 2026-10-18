package ir.hawytech.hawyos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = context.getSharedPreferences("hawy_os_native", Context.MODE_PRIVATE)
            val savedCoursesJson = prefs.getString("courses_json", null)

            if (!savedCoursesJson.isNullOrEmpty()) {
                ScheduleAlarmHelper.scheduleAllCourseAlarms(context, savedCoursesJson)
            }
        }
    }
}
