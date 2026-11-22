package tel.misa.mxpocket.workflow

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.i18n.WorkflowCopy

@Composable
fun WorkflowScreen(controller: WorkflowController) {
    val saved by controller.definitions.collectAsStateWithLifecycle()
    val history by controller.history.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val endpoints by controller.endpoints.collectAsStateWithLifecycle()
    val prompt by controller.effects.prompt.collectAsStateWithLifecycle()
    val activity = LocalContext.current.activity()
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag(AppLocale.current.tag))
    val state = WorkflowUiState(
        definitions = saved, actions = controller.actions(), endpoints = endpoints,
        history = history.map { WorkflowHistoryEntry(it, formatter.format(Date(it.timestamp))) },
        status = status, error = error, prompt = prompt,
    )
    val callbacks = WorkflowCallbacks(
        save = controller::save, delete = controller::delete,
        run = { draft -> controller.validate(draft) ?: if (controller.run(draft.id, WorkflowOrigin.MANUAL)) null
            else WorkflowCopy.CannotStart.text() },
        test = { draft -> controller.validate(draft) ?: if (controller.test(draft)) null
            else WorkflowCopy.AlreadyRunning.text() },
        cancel = controller::cancel,
        respond = { request, accept -> controller.effects.respond(request, accept, activity) },
        endpoints = EndpointCallbacks(
            validate = { if (it.valid()) null else WorkflowCopy.ExactAddressRequired.text() },
            approve = controller::addEndpoint, revoke = controller::removeEndpoint,
        ),
    )
    WorkflowScreen(state, callbacks) { enabled, onBack -> BackHandler(enabled, onBack) }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
