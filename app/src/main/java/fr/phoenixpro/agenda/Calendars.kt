package fr.phoenixpro.agenda

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.concurrent.ConcurrentHashMap

/** Numéro de semaine ISO (celui utilisé en France). */
fun LocalDate.weekNumber(): Int = get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

/** Jours fériés de France métropolitaine. */
object Holidays {
    private val cache = ConcurrentHashMap<Int, Map<LocalDate, String>>()

    /** Dimanche de Pâques (algorithme de Meeus / Jones / Butcher). */
    fun easter(y: Int): LocalDate {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(y, month, day)
    }

    fun of(y: Int): Map<LocalDate, String> = cache.getOrPut(y) {
        val p = easter(y)
        linkedMapOf(
            LocalDate.of(y, 1, 1) to "Jour de l'an",
            p.plusDays(1) to "Lundi de Pâques",
            LocalDate.of(y, 5, 1) to "Fête du Travail",
            LocalDate.of(y, 5, 8) to "Victoire 1945",
            p.plusDays(39) to "Ascension",
            p.plusDays(50) to "Lundi de Pentecôte",
            LocalDate.of(y, 7, 14) to "Fête nationale",
            LocalDate.of(y, 8, 15) to "Assomption",
            LocalDate.of(y, 11, 1) to "Toussaint",
            LocalDate.of(y, 11, 11) to "Armistice 1918",
            LocalDate.of(y, 12, 25) to "Noël",
        )
    }

    fun name(d: LocalDate): String? = of(d.year)[d]
}

/** Le rendez-vous a-t-il lieu ce jour-là (en tenant compte des répétitions) ? */
fun Event.occursOn(d: LocalDate): Boolean {
    val e = d.toEpochDay()
    if (e < day) return false
    if (until >= 0 && e > until) return false
    if (e in skips) return false
    val s = LocalDate.ofEpochDay(day)
    return when (repeat) {
        Repeat.NONE -> e == day
        Repeat.DAILY -> true
        Repeat.WEEKLY -> (e - day) % 7 == 0L
        Repeat.BIWEEKLY -> (e - day) % 14 == 0L
        Repeat.MONTHLY -> {
            val m = ChronoUnit.MONTHS.between(s.withDayOfMonth(1), d.withDayOfMonth(1))
            s.plusMonths(m) == d
        }
        Repeat.YEARLY -> s.plusYears((d.year - s.year).toLong()) == d
    }
}

/** Rendez-vous d'un jour, triés : journée entière d'abord, puis par heure. */
fun eventsOn(d: LocalDate, all: List<Event>): List<Event> =
    all.filter { it.occursOn(d) }.sortedWith(compareBy<Event>({ it.start }, { it.title.lowercase() }))

fun notesOn(d: LocalDate, all: List<Note>): List<Note> {
    val e = d.toEpochDay()
    return all.filter { it.day == e }
}

fun minutesLabel(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

fun Event.timeLabel(): String = when {
    allDay -> "Journée"
    end > start -> "${minutesLabel(start)} – ${minutesLabel(end)}"
    else -> minutesLabel(start)
}
