package io.craftpanel.master.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.database.schema.Servers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ServerScheduler(
    private val handlers: Map<String, ScheduledJobHandler>,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(ServerScheduler::class.java)
    private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            log.info("Scheduler started")
            var consecutiveFailures = 0
            while (isActive) {
                val tickStart = Clock.System.now()
                runCatching { tick(tickStart) }
                    .onSuccess { consecutiveFailures = 0 }
                    .onFailure {
                        consecutiveFailures++
                        log.error("Scheduler tick error (failure $consecutiveFailures)", it)
                        if (consecutiveFailures >= 3) {
                            val backoff = minOf(consecutiveFailures.minutes, 5.minutes)
                            log.warn("Scheduler backing off for $backoff after $consecutiveFailures consecutive failures")
                            delay(backoff)
                            return@onFailure
                        }
                    }
                val elapsed = Clock.System.now() - tickStart
                val remaining = 60.seconds - elapsed
                if (remaining.isPositive()) delay(remaining)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun tick(now: kotlin.time.Instant) {
        val nowZdt = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
            .atZone(ZoneOffset.UTC)
        val nowMinute = nowZdt.truncatedTo(ChronoUnit.MINUTES)

        val backupRows = transaction {
            Servers.selectAll()
                .where { Servers.backupSchedule.isNotNull() }
                .toList()
        }
        for (row in backupRows) {
            val expr = row[Servers.backupSchedule] ?: continue
            if (!fires(expr, nowZdt)) continue
            val lastFired = row[Servers.backupScheduleLastFired]
            if (lastFired != null) {
                val lastFiredMinute = lastFired.toJavaLocalDateTime()
                    .atZone(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MINUTES)
                if (lastFiredMinute == nowMinute) continue
            }
            val serverId = row[Servers.id]
            transaction {
                Servers.update({ Servers.id eq serverId }) {
                    it[backupScheduleLastFired] = now.toLocalDateTime(TimeZone.UTC)
                }
            }
            handlers["BACKUP"]?.let { handler ->
                scope.launch {
                    handler.execute(JobExecutionContext(serverId, jobId = null, scheduledAt = now))
                }
            }
        }

        val genericRows = transaction {
            ServerJobs.selectAll()
                .where { ServerJobs.enabled eq true }
                .toList()
        }
        for (row in genericRows) {
            val expr = row[ServerJobs.cronExpression]
            if (!fires(expr, nowZdt)) continue
            val lastFired = row[ServerJobs.lastFiredAt]
            if (lastFired != null) {
                val lastFiredMinute = lastFired.toJavaLocalDateTime()
                    .atZone(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MINUTES)
                if (lastFiredMinute == nowMinute) continue
            }
            val jobId = row[ServerJobs.id]
            val serverId = row[ServerJobs.serverId]
            val type = row[ServerJobs.type]
            transaction {
                ServerJobs.update({ ServerJobs.id eq jobId }) {
                    it[lastFiredAt] = now.toLocalDateTime(TimeZone.UTC)
                }
            }
            handlers[type]?.let { handler ->
                scope.launch {
                    handler.execute(JobExecutionContext(serverId, jobId = jobId, scheduledAt = now))
                }
            } ?: log.warn("No handler registered for job type '$type' (job $jobId)")
        }
    }

    private fun fires(expression: String, at: ZonedDateTime): Boolean = runCatching {
        val cron = cronParser.parse(expression)
        ExecutionTime.forCron(cron)
            .isMatch(at)
    }.getOrElse { false }
}
