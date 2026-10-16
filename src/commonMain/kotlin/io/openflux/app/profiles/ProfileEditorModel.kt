package io.openflux.app.profiles

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.PhpHosts
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.managed
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How a profile carries traffic: one transport, several at once (Session), or a node on a hosting. */
enum class EditMode(val label: String) { Classic("Обычный"), Session("Session"), Stream("Без сервера") }

val Profile.editMode: EditMode get() = when {
    stream -> EditMode.Stream
    session -> EditMode.Session
    else -> EditMode.Classic
}

/**
 * A profile's fields, changed in a draft (spec 6.3). Transport 0 is the main one, the rest are a Session profile's
 * extras. Saving checks that the profile can connect; a subscription's location is not the person's to change.
 */
@Stable
class ProfileEditorModel(private val container: AppContainer, id: String?, private val scope: CoroutineScope) {
    private val original: Profile? = id?.let { i -> container.profiles.profiles.value.firstOrNull { it.id == i } }
    val isNew: Boolean = original == null
    val readOnly: Boolean = original?.managed == true

    var draft by mutableStateOf(
        original ?: Profile(id = container.profiles.newId(), name = "", transport = TransportType.VYANDEX, createdAt = container.platform.now()),
    )
        private set
    private val initial = draft
    private var saved by mutableStateOf(original)
    var showProblems by mutableStateOf(false)
        private set
    /** The transport whose document or rooms are being made. */
    var busyIndex by mutableStateOf<Int?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val problems: List<String> get() = draft.problems()
    val hasUnsaved: Boolean get() = !readOnly && draft != (saved ?: initial)

    fun edit(transform: (Profile) -> Profile) {
        if (readOnly) return
        draft = transform(draft)
    }

    fun setMode(mode: EditMode) = edit {
        val transport = when {
            mode == EditMode.Stream && it.transport !in PhpHosts.carriers -> TransportType.CUPSONLINE
            mode != EditMode.Session && mode != EditMode.Stream && it.transport.sessionOnly -> TransportType.VYANDEX
            else -> it.transport
        }
        it.copy(
            session = mode == EditMode.Session,
            stream = mode == EditMode.Stream,
            transport = transport,
            // A node on a hosting has no key: nothing of it may linger from the other modes.
            secret = if (mode == EditMode.Stream) "" else it.secret,
            context = if (mode == EditMode.Stream) "" else it.context,
            extras = if (mode == EditMode.Session) it.extras else emptyList(),
        )
    }

    /** The carrier at [index] (0 the main one). */
    fun transport(index: Int): ExtraTransport? = draft.carriers.getOrNull(index)

    fun editTransport(index: Int, transform: (ExtraTransport) -> ExtraTransport) = edit { p ->
        if (index == 0) {
            val c = transform(p.carriers.first())
            p.copy(transport = c.type, value = c.value, uid = c.uid, priority = c.priority, network = c.network, scriptId = c.scriptId, settings = c.settings)
        } else {
            p.copy(extras = p.extras.mapIndexed { i, e -> if (i == index - 1) transform(e) else e })
        }
    }

    fun addTransport() = edit { it.copy(extras = it.extras + ExtraTransport(TransportType.DIRECT, priority = 110)) }

    fun removeTransport(index: Int) = edit { p -> p.copy(extras = p.extras.filterIndexed { i, _ -> i != index - 1 }) }

    fun newKey() = edit { it.copy(secret = container.platform.newSecret()) }

    /** Opens new cups.online rooms for transport [index]. */
    fun makeRooms(index: Int) = work(index, "Не получилось создать комнаты") {
        val rooms = container.platform.newCupsRooms()
        editTransport(index) { it.copy(value = rooms) }
    }

    /** Creates the document transport [index] needs with the service's account (signing in first if needed). */
    fun makeDocument(index: Int) {
        val type = transport(index)?.type ?: return
        val kind = DocumentKind.of(type) ?: return
        work(index, "Не получилось создать документ") {
            val url = try {
                container.accounts.createDocument(kind, NodeDocuments.fileName(draft.name, "", container.platform.now()))
            } catch (e: AccountException) {
                if (e.cancelled) return@work
                throw e
            }
            editTransport(index) { it.copy(value = url) }
        }
    }

    /** Saves when the profile can connect and returns it; otherwise shows what is missing. */
    fun save(): Profile? {
        if (readOnly) return null
        val d = draft.copy(name = draft.name.trim())
        if (d.problems().isNotEmpty()) {
            showProblems = true
            return null
        }
        val ready = if (d.session) d else d.copy(extras = emptyList())
        container.profiles.upsert(ready)
        draft = ready
        saved = ready
        showProblems = false
        return ready
    }

    private fun work(index: Int, failure: String, block: suspend () -> Unit) {
        if (busyIndex != null) return
        busyIndex = index
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: failure
            } finally {
                busyIndex = null
            }
        }
    }
}
