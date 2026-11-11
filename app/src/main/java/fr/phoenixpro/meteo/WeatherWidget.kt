package fr.phoenixpro.meteo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Widget d'écran d'accueil : météo actuelle + 5 jours. */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        val views = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
        RefreshWorker.now(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RefreshWorker.schedule(ctx)
        RefreshWorker.now(ctx)
    }

    companion object {
        private val names = intArrayOf(R.id.d0_name, R.id.d1_name, R.id.d2_name, R.id.d3_name, R.id.d4_name)
        private val icons = intArrayOf(R.id.d0_icon, R.id.d1_icon, R.id.d2_icon, R.id.d3_icon, R.id.d4_icon)
        private val temps = intArrayOf(R.id.d0_temp, R.id.d1_temp, R.id.d2_temp, R.id.d3_temp, R.id.d4_temp)

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WeatherWidget::class.java))
            if (ids.isEmpty()) return
            Store.init(ctx)
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)

            val cache = Store.cache()
            if (cache == null) {
                v.setTextViewText(R.id.w_city, Store.current.name)
                v.setTextViewText(R.id.w_desc, "Chargement…")
                return v
            }
            val (place, f, time) = cache
            val c = f.current
            v.setTextViewText(R.id.w_icon, Wmo.icon(c.code, c.isDay))
            v.setTextViewText(R.id.w_temp, c.temp.deg())
            v.setTextViewText(R.id.w_city, place.name)
            v.setTextViewText(R.id.w_desc, Wmo.text(c.code))
            val today = f.days.firstOrNull()
            val sub = buildString {
                if (today != null) append("${today.tMin.deg()} / ${today.tMax.deg()}")
                if (today != null && today.pop >= 20) append(" · 💧${today.pop}%")
                append(" · ").append(SimpleDateFormat("HH'h'mm", Locale.FRANCE).format(Date(time)))
            }
            v.setTextViewText(R.id.w_sub, sub)

            val fr = Locale.FRANCE
            for (i in 0 until 5) {
                val d = f.days.getOrNull(i) ?: continue
                val date = LocalDate.parse(d.date)
                val name = if (i == 0) "Auj." else DateTimeFormatter.ofPattern("EEE", fr).format(date).replaceFirstChar { it.titlecase(fr) }
                v.setTextViewText(names[i], name)
                v.setTextViewText(icons[i], Wmo.icon(d.code))
                v.setTextViewText(temps[i], "${d.tMax.r()}° ${d.tMin.r()}°")
            }
            return v
        }
    }
}
