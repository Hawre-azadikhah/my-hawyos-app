package ir.hawytech.hawyos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ۱. درخواست مجوز نوتیفیکیشن (اندروید ۱۳+) و حذف محدودیت باتری
        requestPermissionsAndUnrestrictedBattery()

        // ۲. راه‌اندازی WebView تمام‌صفحه برای اجرای HAWY OS
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // تزریق اسکریپت همگام‌سازی خودکار به HTML:
                // هر تغییری در کلاس‌ها یا اساتید رخ دهد، فوراً به کاتلین ارسال می‌شود
                val syncJs = """
                    (function() {
                        if (typeof courses !== 'undefined' && window.Android) {
                            window.Android.syncSchedule(JSON.stringify(courses));
                        }
                        const origSave = window.saveData;
                        if (typeof origSave === 'function') {
                            window.saveData = function() {
                                origSave();
                                if (window.Android) {
                                    window.Android.syncSchedule(JSON.stringify(courses));
                                }
                            };
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(syncJs, null)
            }
        }

        // اتصال پل ارتباطی کاتلین با نام window.Android
        webView.addJavascriptInterface(NativeBridge(this), "Android")

        // بارگذاری فایل HTML از پوشه assets
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestPermissionsAndUnrestrictedBattery() {
        // مجوز اعلان در اندروید ۱۳ به بالا
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // معافیت از بهینه‌سازی باتری برای جلوگیری از بسته شدن تایمرها در شیائومی و سامسونگ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // پل ارتباطی جاوااسکریپت و کاتلین
    class NativeBridge(private val context: Context) {

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            AlarmReceiver.triggerImmediateNotification(context, title, message, 9999)
        }

        @JavascriptInterface
        fun syncSchedule(coursesJson: String) {
            // ذخیره برنامه کلاسی در حافظه بومی کاتلین (برای استفاده در BootReceiver)
            val prefs = context.getSharedPreferences("hawy_os_native", Context.MODE_PRIVATE)
            prefs.edit().putString("courses_json", coursesJson).apply()

            // زمان‌بندی مجدد تمام هشدارهای ۳۰ دقیقه و ۱۰ دقیقه قبل از کلاس
            ScheduleAlarmHelper.scheduleAllCourseAlarms(context, coursesJson)
        }
    }
}

/**
 * کلاس کمکی برای محاسبه زمان دقیق کلاس‌ها در طول هفته و ثبت در AlarmManager هسته اندروید
 */
object ScheduleAlarmHelper {

    fun scheduleAllCourseAlarms(context: Context, coursesJson: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            val array = JSONArray(coursesJson)

            // ابتدا آلارم‌های قبلی را پاک می‌کنیم تا تداخل ایجاد نشود (حداکثر تا ۱۰۰ درس)
            for (i in 0 until 100) {
                cancelSingleAlarm(context, alarmManager, 3000 + i)
                cancelSingleAlarm(context, alarmManager, 1000 + i)
            }

            // ثبت آلارم ۳۰ دقیقه قبل و ۱۰ دقیقه قبل برای تک‌تک کلاس‌ها
            for (i in 0 until array.length()) {
                val course = array.getJSONObject(i)
                val name = course.optString("name", "درس دانشگاه")
                val professor = course.optString("professor", "استاد مربوطه")
                val location = course.optString("location", "کلاس")
                val jsDay = course.optInt("day", 6) // در جاوااسکریپت: یکشنبه=0 تا شنبه=6
                val startStr = course.optString("start", "08:00")

                // ۱. تنظیم هشدار ۳۰ دقیقه قبل از شروع کلاس
                scheduleReminder(
                    context = context,
                    alarmManager = alarmManager,
                    requestCode = 3000 + i,
                    jsDay = jsDay,
                    startTime = startStr,
                    minutesBefore = 30,
                    title = "⏰ ۳۰ دقیقه تا شروع درس «$name»",
                    message = "استاد: $professor | مکان: $location | ساعت شروع: $startStr"
                )

                // ۲. تنظیم هشدار ۱۰ دقیقه قبل از شروع کلاس
                scheduleReminder(
                    context = context,
                    alarmManager = alarmManager,
                    requestCode = 1000 + i,
                    jsDay = jsDay,
                    startTime = startStr,
                    minutesBefore = 10,
                    title = "🚨 فقط ۱۰ دقیقه تا شروع «$name» باقی مانده!",
                    message = "سریع‌تر در $location حاضر شوید (استاد: $professor)."
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelSingleAlarm(context: Context, alarmManager: AlarmManager, requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleReminder(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        jsDay: Int,
        startTime: String,
        minutesBefore: Int,
        title: String,
        message: String
    ) {
        val parts = startTime.split(":")
        if (parts.size != 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        // تبدیل ایندکس روز جاوااسکریپت (0=یکشنبه ... 6=شنبه) به استاندارد Calendar کاتلین (1=یکشنبه ... 7=شنبه)
        val calendarDayOfWeek = jsDay + 1

        val targetCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, calendarDayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // کم کردن ۳۰ دقیقه یا ۱۰ دقیقه از زمان شروع کلاس
            add(Calendar.MINUTE, -minutesBefore)
        }

        // اگر زمان هشدار در این هفته گذشته است، آن را برای هفته آینده تنظیم کن
        if (targetCal.timeInMillis <= System.currentTimeMillis()) {
            targetCal.add(Calendar.DAY_OF_YEAR, 7)
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", requestCode)
            putExtra("TITLE", title)
            putExtra("MESSAGE", message)
            putExtra("JS_DAY", jsDay)
            putExtra("START_TIME", startTime)
            putExtra("MINUTES_BEFORE", minutesBefore)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ثبت آلارم دقیق بدون محدودیت (عبور از Doze Mode و صفحه قفل)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                targetCal.timeInMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                targetCal.timeInMillis,
                pendingIntent
            )
        }
    }
}
