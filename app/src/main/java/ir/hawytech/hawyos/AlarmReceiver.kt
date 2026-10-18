package ir.hawytech.hawyos

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import kotlin.random.Random

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("ALARM_ID", Random.nextInt(10000))
        val title = intent.getStringExtra("TITLE") ?: "هشدار کلاس | HAWY OS"
        val message = intent.getStringExtra("MESSAGE") ?: "کلاس شما به زودی آغاز می‌شود."

        // ۱. نمایش اعلان فوری روی صفحه قفل و نوار وضعیت
        triggerImmediateNotification(context, title, message, alarmId)

        // ۲. تمدید خودکار همین آلارم برای هفته آینده
        val jsDay = intent.getIntExtra("JS_DAY", -1)
        val startTime = intent.getStringExtra("START_TIME")
        val minutesBefore = intent.getIntExtra("MINUTES_BEFORE", 30)

        if (jsDay != -1 && startTime != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            ScheduleAlarmHelper.scheduleReminder(
                context = context,
                alarmManager = alarmManager,
                requestCode = alarmId,
                jsDay = jsDay,
                startTime = startTime,
                minutesBefore = minutesBefore,
                title = title,
                message = message
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hawy_os_exact_alarms"

        fun triggerImmediateNotification(context: Context, title: String, message: String, notifId: Int) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "هشدارهای ۳۰ و ۱۰ دقیقه قبل از کلاس (HAWY OS)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "اعلان‌های دقیق شروع کلاس و نام استاد"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 500, 200, 600)
                    setSound(alarmSound, audioAttr)
                }
                manager.createNotificationChannel(channel)
            }

            // باز شدن برنامه در صورت ضربه زدن روی نوتیفیکیشن
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                notifId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context).apply {
                    setPriority(Notification.PRIORITY_MAX)
                    setSound(alarmSound)
                    setVibrate(longArrayOf(0, 400, 200, 500))
                }
            }

            val notification = builder
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .build()

            manager.notify(notifId, notification)
        }
    }
}
