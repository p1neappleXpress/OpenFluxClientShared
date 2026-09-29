package io.openflux.desktop.ui.profiles

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.AccountKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.ui.node.NodeWizardModel
import kotlinx.coroutines.launch

/** A profile being edited; [isNew] until it is saved once. */
data class EditorState(val draft: Profile, val isNew: Boolean, val showProblems: Boolean = false)

/** What the import dialog found in the text it was given. */
sealed interface ImportPreview {
    data object Empty : ImportPreview
    data class Ready(val config: ShareConfig, val source: ProfileSource) : ImportPreview
    data class Invalid(val message: String) : ImportPreview
}

class ProfilesScreenModel(private val container: AppContainer) : ScreenModel {
    val profiles = container.profiles
    val connection = container.connection
    val settings = container.settings
    val platform = container.platform

    var query by mutableStateOf("")
    var selectedId by mutableStateOf<String?>(null)
    var editor by mutableStateOf<EditorState?>(null)
        private set
    var importOpen by mutableStateOf(false)
    /** A link the import dialog starts with (opened from outside), else the clipboard's. */
    var importText: String? = null
    var shareFor by mutableStateOf<Profile?>(null)
    var deleteFor by mutableStateOf<Profile?>(null)
    /** The "Своя нода" wizard while it is open. */
    var wizard by mutableStateOf<NodeWizardModel?>(null)
        private set

    fun filter(list: List<Profile>): List<Profile> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list
        return list.filter { p ->
            p.name.lowercase().contains(q) || p.summary.lowercase().contains(q) ||
                p.carriers.any { it.value.lowercase().contains(q) }
        }
    }

    fun select(profile: Profile) {
        if (editor != null && editor?.draft?.id != profile.id) editor = null
        selectedId = profile.id
    }

    fun startNew() {
        editor = EditorState(
            Profile(id = profiles.newId(), name = "", transport = TransportType.VYANDEX, createdAt = platform.now()),
            isNew = true,
        )
        selectedId = null
    }

    fun startEdit(profile: Profile) {
        selectedId = profile.id
        editor = EditorState(profile, isNew = false)
    }

    val accounts = container.accounts
    /** The carrier (0 the main one) whose document is being created. */
    var documentBusy by mutableStateOf<Int?>(null)
        private set
    var documentError by mutableStateOf<String?>(null)
        private set
    var documentErrorIndex by mutableStateOf<Int?>(null)
        private set

    /** Creates a document with the carrier's account and puts its link into the carrier. */
    fun createDocumentFor(index: Int) {
        val draft = editor?.draft ?: return
        val type = if (index == 0) draft.transport else draft.extras.getOrNull(index - 1)?.type ?: return
        val kind = AccountKind.of(type) ?: return
        documentBusy = index
        documentError = null
        screenModelScope.launch {
            try {
                val url = accounts.createDocument(kind, NodeDocuments.fileName(draft.name, "", platform.now()))
                updateDraft { p ->
                    if (index == 0) p.copy(value = url)
                    else p.copy(extras = p.extras.mapIndexed { i, e -> if (i == index - 1) e.copy(value = url) else e })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? AccountException)?.cancelled == true) return@launch
                documentError = e.message ?: "Не получилось создать документ"
                documentErrorIndex = index
            } finally {
                documentBusy = null
            }
        }
    }

    /** Opens new cups.online rooms and puts their packed list into the carrier. */
    fun generateRoomsFor(index: Int) {
        if (editor == null) return
        documentBusy = index
        documentError = null
        screenModelScope.launch {
            try {
                val rooms = platform.newCupsRooms()
                updateDraft { p ->
                    if (index == 0) p.copy(value = rooms)
                    else p.copy(extras = p.extras.mapIndexed { i, e -> if (i == index - 1) e.copy(value = rooms) else e })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                documentError = e.message ?: "Не получилось создать комнаты"
                documentErrorIndex = index
            } finally {
                documentBusy = null
            }
        }
    }

    fun signIn(kind: AccountKind) {
        screenModelScope.launch { runCatching { accounts.signIn(kind) } }
    }

    fun updateDraft(transform: (Profile) -> Profile) {
        editor = editor?.let { it.copy(draft = transform(it.draft)) }
    }

    fun cancelEdit() {
        editor = null
    }

    /** Saves the draft when it can connect; otherwise shows what is missing. */
    fun save(): Boolean {
        val state = editor ?: return false
        val draft = state.draft.copy(name = state.draft.name.trim())
        if (draft.problems().isNotEmpty()) {
            editor = state.copy(showProblems = true)
            return false
        }
        profiles.upsert(if (draft.session) draft else draft.copy(extras = emptyList()))
        selectedId = draft.id
        editor = null
        return true
    }

    fun duplicate(profile: Profile) {
        val copy = profile.copy(id = profiles.newId(), name = "${profile.name} (копия)", createdAt = platform.now(), source = ProfileSource.Manual)
        profiles.upsert(copy)
        selectedId = copy.id
    }

    fun delete(profile: Profile) {
        if (connection.state.value.profile?.id == profile.id && connection.state.value.isActive) connection.disconnect()
        profiles.delete(profile.id)
        if (selectedId == profile.id) selectedId = null
        if (editor?.draft?.id == profile.id) editor = null
        if (settings.settings.value.selectedProfileId == profile.id) settings.update { it.copy(selectedProfileId = null) }
    }

    fun connect(profile: Profile) {
        settings.update { it.copy(selectedProfileId = profile.id) }
        connection.connect(profile)
    }

    fun isRunning(profile: Profile): Boolean {
        val state = connection.state.value
        return state.isActive && state.profile?.id == profile.id
    }

    // ---- own node ----

    fun openWizard() {
        if (wizard == null) wizard = NodeWizardModel(container, screenModelScope)
        editor = null
    }

    /** Leaves the wizard; [focusId] selects the profile it saved. */
    fun closeWizard(focusId: String? = null) {
        wizard?.close()
        wizard = null
        if (focusId != null) selectedId = focusId
    }

    override fun onDispose() {
        wizard?.close()
    }

    // ---- import ----

    /** The core reads the link (off the UI thread: on the desktop it runs the core). */
    suspend fun preview(text: String, source: ProfileSource = ProfileSource.Link): ImportPreview {
        if (text.isBlank()) return ImportPreview.Empty
        return try {
            ImportPreview.Ready(container.shareCodec.decode(text), source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportPreview.Invalid(e.message ?: "Не удалось прочитать ссылку")
        }
    }

    fun clipboardText(): String = platform.clipboardText().orEmpty().trim()

    fun qrFromClipboard(): String? = platform.qrFromClipboardImage()

    suspend fun qrFromFile(): Pair<String?, Boolean> {
        val path = platform.pickFile("QR-код OpenFlux", listOf("png", "jpg", "jpeg", "bmp", "gif")) ?: return null to false
        return platform.qrFromFile(path) to true
    }

    suspend fun scanQr(): String? = platform.scanQr()

    fun import(preview: ImportPreview.Ready): Profile {
        val profile = Profile.fromShare(preview.config, profiles.newId(), platform.now(), preview.source)
        profiles.upsert(profile)
        selectedId = profile.id
        importOpen = false
        return profile
    }

    // ---- sharing ----

    /** The link the core makes for [profile], or why it cannot be shared. */
    suspend fun shareLink(profile: Profile): Result<String> {
        val config = profile.toShare().getOrElse { return Result.failure(it) }
        return try {
            Result.success(container.shareCodec.encode(config))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun copyLink(profile: Profile) {
        screenModelScope.launch { shareLink(profile).onSuccess(::copy) }
    }

    fun copy(text: String) = platform.setClipboardText(text)

    fun qr(text: String) = platform.qrMatrix(text)

    fun newSecret() = platform.newSecret()
}
