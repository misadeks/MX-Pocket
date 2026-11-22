package tel.misa.mxpocket

const val FOREGROUND_APP = "foregroundApp"
const val APP_RULE_WEIGHT = 5 // Stock media scores 3; changing a positive incumbent needs a lead of 2.

/** Transient Usage Access evidence. Resume is wall time; dwell ends at the usage query, not its delivery. */
data class ForegroundMatch(val packageName: String, val resumedAtMillis: Long, val unchangedForMillis: Long)
