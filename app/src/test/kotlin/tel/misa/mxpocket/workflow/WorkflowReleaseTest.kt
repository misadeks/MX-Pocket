package tel.misa.mxpocket.workflow

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tel.misa.mxpocket.PocketApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WorkflowReleaseTest.WorkerOnlyApp::class)
class WorkflowReleaseTest {
    class WorkerOnlyApp : PocketApplication() {
        override fun createSession() = error("A disabled worker must not open a keypad session")
    }
    class OtherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork() = Result.success()
    }

    @Test fun upgradeCancelsOnlyWorkflowJobsAndRacingDeliveryDoesNotRequeueOrRecord() {
        val app = RuntimeEnvironment.getApplication()
        val direct = java.util.concurrent.Executor { it.run() }
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(direct)
            .setTaskExecutor(direct).build())
        val wm = WorkManager.getInstance(app)
        val daily = WorkflowDefinition.demo.copy(trigger = WorkflowTrigger(TriggerKind.DAILY, "00:00"))
        val library = WorkflowLibrary(1, listOf(daily))
        WorkflowStore(app).save(library)
        val input = workDataOf("workflow" to daily.id, "time" to "00:00", "day" to LocalDate.now().toString())
        val old = OneTimeWorkRequestBuilder<WorkflowWorker>().setInputData(input).setInitialDelay(1, TimeUnit.DAYS).build()
        val other = OneTimeWorkRequestBuilder<OtherWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        wm.enqueue(listOf(old, other)).result.get()
        WorkflowScheduleInitializer().create(app)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(old.id).get()?.state)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(other.id).get()?.state)

        val racing = TestListenableWorkerBuilder<WorkflowWorker>(app, inputData = input).build()
        assertEquals(Result.success(), runBlocking { racing.doWork() })
        queueDaily(app, daily)
        assertEquals(listOf(old.id), wm.getWorkInfosByTag(WorkflowWorker::class.java.name).get().map { it.id })
        assertTrue(app.getSharedPreferences("workflow-deliveries", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(library, WorkflowStore(app).load())
    }
}
