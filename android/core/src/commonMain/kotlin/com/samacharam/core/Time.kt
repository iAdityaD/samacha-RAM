package com.samacharam.core

const val DAY_MS = 86_400_000L

private val ISO = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?$"
)

/** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

/** Parses ISO-8601 dates/timestamps as written by the Python host. Returns epoch ms (UTC) or null. */
fun parseIsoInstant(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val m = ISO.matchEntire(value.trim()) ?: return null
    val g = m.groupValues
    val month = g[2].toInt(); val day = g[3].toInt()
    if (month !in 1..12 || day !in 1..31) return null
    val days = daysFromCivil(g[1].toInt(), month, day)
    val hours = g[4].ifEmpty { "0" }.toInt(); val minutes = g[5].ifEmpty { "0" }.toInt(); val seconds = g[6].ifEmpty { "0" }.toInt()
    if (hours > 23 || minutes > 59 || seconds > 60) return null
    val millis = g[7].ifEmpty { "0" }.padEnd(3, '0').take(3).toInt()
    var offsetMinutes = 0
    val zone = g[8]
    if (zone.isNotEmpty() && zone != "Z") {
        val digits = zone.substring(1).replace(":", "")
        offsetMinutes = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * if (zone[0] == '-') -1 else 1
    }
    return days * DAY_MS + ((hours * 60L + minutes - offsetMinutes) * 60 + seconds) * 1000 + millis
}

/** yyyy-MM-dd in UTC. */
fun formatDate(epochMs: Long): String {
    val z = epochMs.floorDiv(DAY_MS) + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = mp + if (mp < 10) 3 else -9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
