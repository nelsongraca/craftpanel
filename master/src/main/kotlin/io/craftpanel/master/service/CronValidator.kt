package io.craftpanel.master.service

/**
 * Shared validation for the 5-field Unix cron expressions accepted by backup schedules and
 * user-defined scheduled jobs. Kept in one place so both paths enforce identical syntax.
 */
object CronValidator {

    private val REGEX = Regex("""^(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)$""")

    fun isValid(expression: String): Boolean = REGEX.matches(expression)
}
