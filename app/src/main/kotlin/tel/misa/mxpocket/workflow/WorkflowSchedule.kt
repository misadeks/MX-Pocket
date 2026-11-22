package tel.misa.mxpocket.workflow

import android.content.Context
import androidx.startup.Initializer
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkflowScheduleInitializer : Initializer<Unit> {
    override fun dependencies() = listOf(WorkManagerInitializer::class.java)

    override fun create(context: Context) {
        if (!WORKFLOWS_ENABLED) {
            // WorkManager tags requests with the worker class name, including jobs from older releases.
            WorkManager.getInstance(context).cancelAllWorkByTag(WorkflowWorker::class.java.name)
        }
    }
}

internal fun nextOccurrence(time: LocalTime, now: ZonedDateTime): ZonedDateTime {
    fun on(day: Long) = now.toLocalDate().plusDays(day).atTime(time).atZone(now.zone)
    return on(0).takeIf { it > now } ?: on(1)
}

internal fun queueDaily(context: Context, definition: WorkflowDefinition) {
    if (!WORKFLOWS_ENABLED) return
    val now = ZonedDateTime.now()
    val time = runCatching { LocalTime.parse(definition.trigger.value) }.getOrNull() ?: return
    val next = nextOccurrence(time, now)
    val day = next.toLocalDate().toString()
    val input = workDataOf("workflow" to definition.id, "time" to time.toString(), "day" to day)
    val request = OneTimeWorkRequestBuilder<WorkflowWorker>().setInputData(input)
        .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
    WorkManager.getInstance(context).enqueueUniqueWork("workflow:${definition.id}:$day:$time", ExistingWorkPolicy.KEEP, request)
}

class WorkflowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        if (!WORKFLOWS_ENABLED) return@withContext Result.success()
        val controller = (applicationContext as? WorkflowProvider)?.workflows ?: return@withContext Result.failure()
        val definition = controller.definitions.value.find { it.id == inputData.getString("workflow") }
            ?: return@withContext Result.success()
        if (definition.trigger != WorkflowTrigger(TriggerKind.DAILY, inputData.getString("time").orEmpty()))
            return@withContext Result.success() // Edited/deleted schedules do not execute stale deliveries.
        queueDaily(applicationContext, definition)
        val now = ZonedDateTime.now()
        if (now.toLocalDate().toString() != inputData.getString("day") ||
            now.toLocalTime() < LocalTime.parse(definition.trigger.value)) return@withContext Result.success()
        // WorkManager may redeliver interrupted work. Do not replay a partially completed side effect.
        val receipts = applicationContext.getSharedPreferences("workflow-deliveries", Context.MODE_PRIVATE)
        val slot = "${now.toLocalDate()}:${definition.trigger.value}"
        if (receipts.getString(definition.id, null) == slot) return@withContext Result.success()
        if (!receipts.edit().putString(definition.id, slot).commit()) return@withContext Result.failure()
        val job = controller.start(definition, WorkflowOrigin.SCHEDULE) ?: return@withContext Result.failure()
        try { job.join() } finally { if (!job.isCompleted) job.cancel() }
        if (controller.status.value?.phase == WorkflowPhase.SUCCESS) Result.success() else Result.failure()
    }
}
