package fr.phoenixpro.agenda

enum class Repeat(val label: String) {
    NONE("Jamais"),
    DAILY("Chaque jour"),
    WEEKLY("Chaque semaine"),
    BIWEEKLY("Toutes les 2 semaines"),
    MONTHLY("Chaque mois"),
    YEARLY("Chaque année"),
}

/**
 * Un rendez-vous.
 * [day] = jour (epochDay) de la première occurrence.
 * [start]/[end] = minutes depuis minuit ; -1 = journée entière.
 * [reminder] = minutes avant le rendez-vous ; -1 = pas de rappel.
 * [skips] = jours (epochDay) supprimés d'une série répétée.
 */
data class Event(
    val id: Long,
    val title: String,
    val day: Long,
    val start: Int = -1,
    val end: Int = -1,
    val place: String = "",
    val note: String = "",
    val category: Int = 0,
    val repeat: Repeat = Repeat.NONE,
    val until: Long = -1,
    val reminder: Int = -1,
    val skips: Set<Long> = emptySet(),
) {
    val allDay: Boolean get() = start < 0
}

/** Une note, libre ou rattachée à un jour ([day] = epochDay, -1 = aucun). */
data class Note(
    val id: Long,
    val title: String,
    val text: String,
    val day: Long = -1,
    val updated: Long = System.currentTimeMillis(),
)

data class Settings(
    val zone: String = "B",
    val showSchool: Boolean = true,
    val defaultReminder: Int = 15,
    val categories: List<String> = DEFAULT_CATEGORIES,
)

val DEFAULT_CATEGORIES = listOf("Travail", "CSE", "Perso", "Famille", "Santé", "Autre")

/** Couleurs des catégories (ARGB), dans l'ordre de [DEFAULT_CATEGORIES]. */
val CATEGORY_COLORS = longArrayOf(
    0xFF1D63C4, // bleu Phoenix
    0xFF7B3FBF, // violet
    0xFF1BA39C, // turquoise
    0xFFF08A1C, // orange
    0xFFD93F3F, // rouge
    0xFF6B7280, // gris
)

fun categoryColor(i: Int): Int = CATEGORY_COLORS[i.coerceIn(0, CATEGORY_COLORS.size - 1)].toInt()

/** Choix de rappel proposés (minutes avant). */
val REMINDER_CHOICES = listOf(-1, 0, 5, 15, 30, 60, 120, 1440, 2880, 10080)

fun reminderLabel(m: Int, allDay: Boolean): String = when {
    m < 0 -> "Aucun rappel"
    allDay && m == 0 -> "Le jour même à 9 h"
    allDay && m == 1440 -> "La veille à 9 h"
    allDay && m == 2880 -> "2 jours avant à 9 h"
    allDay && m == 10080 -> "1 semaine avant à 9 h"
    m == 0 -> "À l'heure du rendez-vous"
    m < 60 -> "$m minutes avant"
    m == 60 -> "1 heure avant"
    m < 1440 -> "${m / 60} heures avant"
    m == 1440 -> "La veille (24 h avant)"
    m == 2880 -> "2 jours avant"
    m == 10080 -> "1 semaine avant"
    else -> "$m minutes avant"
}
