package tel.misa.mxpocket.workflow

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationManagerCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal data class WorkflowPrompt(val text: String, val openUrl: String? = null)

internal class AndroidWorkflowEffects(private val app: Context) : WorkflowEffects {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(8, TimeUnit.SECONDS).build()
    private val current = MutableStateFlow<WorkflowPrompt?>(null)
    val prompt = current.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null
    override suspend fun confirm(prompt: String) = ask(WorkflowPrompt(prompt))
    override suspend fun openUrl(url: String) = safeHttps(url, endpoint = false) &&
        ask(WorkflowPrompt("Open this HTTPS page in your browser?\n$url", url))

    private suspend fun ask(request: WorkflowPrompt): Boolean {
        val pending = CompletableDeferred<Boolean>()
        check(answer == null)
        answer = pending
        current.value = request
        return try { pending.await() } finally { current.value = null; answer = null }
    }

    fun respond(request: WorkflowPrompt, accept: Boolean, activity: Activity?) {
        if (current.value !== request) return
        var ok = accept && activity != null && !activity.isFinishing && !activity.isDestroyed
        if (ok && request.openUrl != null) {
            try {
                activity!!.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.openUrl)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                })
            } catch (_: ActivityNotFoundException) { ok = false } catch (_: SecurityException) { ok = false }
        }
        answer?.complete(ok)
    }

    override suspend fun request(endpoint: WorkflowEndpoint): RequestResult {
        if (!endpoint.valid()) return RequestResult.REFUSED
        // Mark even an empty POST body one-shot: a transport follow-up must not replay it.
        val body = if (endpoint.method == "POST") object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = Unit
        } else null
        val request = Request.Builder().url(endpoint.url).method(endpoint.method, body).build()
        return suspendCancellableCoroutine { result ->
            val call = client.newCall(request)
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { result.resume(RequestResult.TRANSIENT) }
                override fun onResponse(call: Call, response: Response) {
                    val outcome = response.use { when {
                        it.isSuccessful -> RequestResult.SUCCESS
                        it.code in 500..599 -> RequestResult.TRANSIENT
                        else -> RequestResult.REFUSED
                    } }
                    result.resume(outcome)
                }
            })
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked via areNotificationsEnabled; no permission prompts in workflows.
    override fun notify(text: String): Boolean {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        val manager = app.getSystemService(NotificationManager::class.java)
        val id = "workflow-results"
        manager.createNotificationChannel(NotificationChannel(id, "Workflow messages", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(id).importance == NotificationManager.IMPORTANCE_NONE) return false
        manager.notify(4401, Notification.Builder(app, id).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MX Pocket workflow").setContentText(text).setAutoCancel(true).build())
        return true
    }

    override suspend fun speak(text: String): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val engine = TextToSpeech(app) { ready.complete(it == TextToSpeech.SUCCESS) }
        try {
            if (!ready.await()) return false
            val done = CompletableDeferred<Boolean>()
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
                @Deprecated("Required by Android")
                override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            })
            return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS && done.await()
        } finally { engine.stop(); engine.shutdown() }
    }
}
