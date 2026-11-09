package fr.phoenixpro.agenda

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import fr.phoenixpro.agenda.ui.MainActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Widget d'écran d'accueil : date du jour, n° de semaine et prochains rendez-vous. */
class AgendaWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        val views = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    companion object {
        private val rowIds = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4)
        private val dotIds = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4)
        private val whenIds = intArrayOf(R.id.when0, R.id.when1, R.id.when2, R.id.when3, R.id.when4)
        private val titleIds = intArrayOf(R.id.title0, R.id.title1, R.id.title2, R.id.title3, R.id.title4)

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AgendaWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun build(ctx: Context): RemoteViews {
            val fr = Locale.FRANCE
            val today = LocalDate.now()
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(
                R.id.w_date,
                DateTimeFormatter.ofPattern("EEEE d MMMM", fr).format(today).replaceFirstChar { it.titlecase(fr) },
            )
            val s = Store.settings.value
            val extra = Holidays.name(today) ?: if (s.showSchool) School.on(today, s.zone)?.name else null
            v.setTextViewText(R.id.w_sub, "Semaine ${today.weekNumber()}" + (extra?.let { " · $it" } ?: ""))

            // prochains rendez-vous sur 30 jours
            val items = ArrayList<Pair<LocalDate, Event>>()
            val all = Store.events.value
            var d = today
            val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            while (items.size < rowIds.size && d.isBefore(today.plusDays(30))) {
                for (e in eventsOn(d, all)) {
                    if (d == today && !e.allDay && maxOf(e.start, e.end) < nowMin) continue
                    items.add(d to e)
                    if (items.size == rowIds.size) break
                }
                d = d.plusDays(1)
            }
            rowIds.indices.forEach { i ->
                if (i < items.size) {
                    val (day, e) = items[i]
                    val dayTxt = when (day) {
                        today -> "Auj."
                        today.plusDays(1) -> "Dem."
                        else -> DateTimeFormatter.ofPattern("EEE d", fr).format(day).replaceFirstChar { it.titlecase(fr) }
                    }
                    v.setViewVisibility(rowIds[i], View.VISIBLE)
                    v.setTextColor(dotIds[i], categoryColor(e.category))
                    v.setTextViewText(whenIds[i], if (e.allDay) dayTxt else "$dayTxt ${minutesLabel(e.start)}")
                    v.setTextViewText(titleIds[i], e.title.ifBlank { "Rendez-vous" })
                } else {
                    v.setViewVisibility(rowIds[i], View.GONE)
                }
            }
            v.setViewVisibility(R.id.w_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}
