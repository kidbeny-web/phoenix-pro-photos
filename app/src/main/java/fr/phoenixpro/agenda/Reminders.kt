package fr.phoenixpro.agenda

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.phoenixpro.agenda.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Programme une alarme pour le prochain rappel de chaque rendez-vous. */
object Reminders {
    const val CHANNEL = "rappels"
    private const val PREFS = "rappels"
    private const val KEY_IDS = "ids"

    fun triggerAt(e: Event, d: LocalDate): Long {
        val base = if (e.allDay) d.atTime(9, 0) else d.atStartOfDay().plusMinutes(e.start.toLong())
        return base.minusMinutes(e.reminder.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Prochaine occurrence dont le rappel est encore à venir. */
    fun next(e: Event, now: Long = System.currentTimeMillis()): Pair<LocalDate, Long>? {
        if (e.reminder < 0) return null
        var d = LocalDate.now()
        repeat(800) {
            if (e.until >= 0 && d.toEpochDay() > e.until) return null
            if (e.occursOn(d)) {
                val t = triggerAt(e, d)
                if (t > now) return d to t
                if (e.repeat == Repeat.NONE) return null
            }
            d = d.plusDays(1)
        }
        return null
    }

    private fun code(id: Long) = (id xor (id ushr 32)).toInt()

    private fun pending(ctx: Context, id: Long, day: Long): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("day", day)
        return PendingIntent.getBroadcast(ctx, code(id), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Synchronized
    fun rescheduleAll(ctx: Context) {
        Store.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // annule les anciennes alarmes
        prefs.getStringSet(KEY_IDS, emptySet())?.forEach { s ->
            s.toLongOrNull()?.let { am.cancel(pending(ctx, it, 0)) }
        }
        val ids = HashSet<String>()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        for (e in Store.events.value) {
            val (d, t) = next(e) ?: continue
            val pi = pending(ctx, e.id, d.toEpochDay())
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                ids.add(e.id.toString())
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                ids.add(e.id.toString())
            }
        }
        prefs.edit().putStringSet(KEY_IDS, ids).apply()
    }

    fun show(ctx: Context, e: Event, d: LocalDate) {
        val fr = Locale.FRANCE
        val today = LocalDate.now()
        val dayTxt = when (d) {
            today -> "Aujourd'hui"
            today.plusDays(1) -> "Demain"
            else -> DateTimeFormatter.ofPattern("EEEE d MMMM", fr).format(d).replaceFirstChar { it.titlecase(fr) }
        }
        val whenTxt = if (e.allDay) dayTxt else "$dayTxt à ${minutesLabel(e.start)}"
        val text = if (e.place.isNotBlank()) "$whenTxt · ${e.place}" else whenTxt
        val open = PendingIntent.getActivity(
            ctx, code(e.id),
            Intent(ctx, MainActivity::class.java).putExtra("day", d.toEpochDay())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(categoryColor(e.category))
            .setContentTitle(e.title.ifBlank { "Rendez-vous" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (e.note.isNotBlank()) "$text\n${e.note}" else text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(code(e.id), n)
        } catch (_: SecurityException) {
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val id = intent.getLongExtra("id", 0)
        val day = intent.getLongExtra("day", LocalDate.now().toEpochDay())
        Store.events.value.firstOrNull { it.id == id }?.let { Reminders.show(ctx, it, LocalDate.ofEpochDay(day)) }
        Reminders.rescheduleAll(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Reminders.rescheduleAll(ctx)
        AgendaWidget.updateAll(ctx)
    }
}
