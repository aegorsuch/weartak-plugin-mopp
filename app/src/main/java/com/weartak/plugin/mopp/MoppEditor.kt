package com.weartak.plugin.mopp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class Posture(val wireValue: String) {
    NONE("none"), MOPP0("0"), MOPP1("1"), MOPP2("2"), MOPP3("3"), MOPP4("4");
}

// Editor-domain types only. The shared SDK, not this module, owns IPC encoding.
data class SavedPosture(
    val revision: Long,
    val selection: Posture?,
    val savedAtEpochMillis: Long?,
    val enabled: Boolean,
    val contributing: Boolean,
    val lastMutationId: String?,
) {
    fun isValid() = revision >= 0 &&
        (savedAtEpochMillis == null || (selection != null && savedAtEpochMillis >= 0)) &&
        (!contributing || (enabled && selection != null))
}

enum class ResultCode {
    OK, REVISION_CONFLICT, INVALID_REQUEST, UNAUTHORIZED, INACTIVE_SESSION, PERSISTENCE_FAILED, PROTOCOL_ERROR
}

data class HostResult(val accepted: Boolean, val code: ResultCode, val state: SavedPosture?)

interface MoppHost {
    fun read(): HostResult
    fun save(expectedRevision: Long, mutationId: String, selection: Posture): HostResult
    fun setEnabled(expectedRevision: Long, mutationId: String, enabled: Boolean): HostResult
}

class HostTransportException(cause: Exception) : Exception(cause)

enum class Availability { DISCONNECTED, UPDATE_WEARTAK, READY }
enum class EditorMessage {
    SAVED, RECONCILED, NOT_RECONCILED, CONFLICT, INVALID, DENIED,
    PERSISTENCE, TRANSPORT, UNCONFIRMED, PROTOCOL
}

data class EditorState(
    val availability: Availability = Availability.DISCONNECTED,
    val saved: SavedPosture? = null,
    val draft: Posture? = null,
    val busy: Boolean = false,
    val needsRead: Boolean = true,
    val message: EditorMessage? = null,
) {
    val canToggle: Boolean
        get() = availability == Availability.READY && !busy && !needsRead && saved != null

    val canSelect: Boolean
        get() = canToggle && saved?.enabled == true
}

class MoppEditor(private val nextMutationId: () -> String = { UUID.randomUUID().toString() }) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(EditorState())
    val state = mutableState.asStateFlow()
    private var host: MoppHost? = null
    private var generation = 0L
    private var pending: Mutation? = null

    private data class Mutation(
        val id: String,
        val selection: Posture? = null,
        val enabled: Boolean? = null,
    ) {
        fun matches(state: SavedPosture) = state.lastMutationId == id &&
            (selection == null || state.selection == selection) &&
            (enabled == null || state.enabled == enabled)
    }

    fun attach(host: MoppHost) = synchronized(lock) {
        generation++
        this.host = host
        pending = null
        mutableState.value = EditorState(availability = Availability.READY)
    }

    fun disconnect(availability: Availability = Availability.DISCONNECTED) = synchronized(lock) {
        require(availability != Availability.READY)
        generation++
        host = null
        pending = null
        mutableState.value = EditorState(availability = availability)
    }

    fun cancelDraft() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(draft = null, message = null)
    }

    fun read() {
        val operation = synchronized(lock) {
            val client = host ?: return
            if (mutableState.value.busy) return
            mutableState.value = mutableState.value.copy(busy = true)
            Operation(client, generation, null)
        }
        perform(operation) { it.read() }
    }

    fun save(selection: Posture) {
        val operation = synchronized(lock) {
            val current = mutableState.value
            val client = host ?: return
            if (!current.canSelect) return
            val saved = current.saved ?: return
            val mutation = Mutation(nextMutationId(), selection = selection)
            pending = mutation
            mutableState.value = current.copy(draft = selection, busy = true, message = null)
            Operation(client, generation, mutation, saved.revision)
        }
        perform(operation) { it.save(operation.revision, operation.mutation!!.id, operation.mutation.selection!!) }
    }

    fun setEnabled(enabled: Boolean) {
        val operation = synchronized(lock) {
            val current = mutableState.value
            val client = host ?: return
            if (!current.canToggle) return
            val saved = current.saved ?: return
            if (saved.enabled == enabled) return
            val mutation = Mutation(nextMutationId(), enabled = enabled)
            pending = mutation
            mutableState.value = current.copy(busy = true, message = null)
            Operation(client, generation, mutation, saved.revision)
        }
        perform(operation) { it.setEnabled(operation.revision, operation.mutation!!.id, enabled) }
    }

    private data class Operation(
        val host: MoppHost,
        val generation: Long,
        val mutation: Mutation?,
        val revision: Long = 0,
    )

    private fun perform(operation: Operation, call: (MoppHost) -> HostResult) {
        val result = try {
            call(operation.host)
        } catch (error: HostTransportException) {
            synchronized(lock) {
                if (generation == operation.generation) {
                    mutableState.value = mutableState.value.copy(
                        busy = false, needsRead = true, message = EditorMessage.TRANSPORT,
                    )
                }
            }
            return
        }
        val readConflict = synchronized(lock) {
            if (generation != operation.generation) return
            val current = mutableState.value
            val authoritative = result.state
            if (result.code == ResultCode.UNAUTHORIZED || result.code == ResultCode.INACTIVE_SESSION) {
                disconnect()
                mutableState.value = mutableState.value.copy(message = EditorMessage.DENIED)
                return
            }
            if (result.accepted != (result.code == ResultCode.OK) ||
                (authoritative != null && !authoritative.isValid()) ||
                (result.accepted && authoritative == null) ||
                (authoritative != null && current.saved != null &&
                    authoritative.revision < current.saved.revision)
            ) {
                mutableState.value = current.copy(
                    busy = false, needsRead = true, message = EditorMessage.PROTOCOL,
                )
                return
            }
            if (result.code == ResultCode.REVISION_CONFLICT) {
                pending = null
                mutableState.value = current.copy(
                    saved = authoritative ?: current.saved,
                    busy = false, needsRead = true, message = EditorMessage.CONFLICT,
                )
                operation.mutation != null
            } else if (!result.accepted) {
                pending = null
                mutableState.value = current.copy(
                    busy = false, needsRead = true,
                    message = when (result.code) {
                        ResultCode.INVALID_REQUEST -> EditorMessage.INVALID
                        ResultCode.PERSISTENCE_FAILED -> EditorMessage.PERSISTENCE
                        else -> EditorMessage.PROTOCOL
                    },
                )
                false
            } else {
                val saved = checkNotNull(authoritative)
                val unresolved = pending
                // Even an OK reply must confirm the exact mutation before clearing a draft.
                if (operation.mutation != null &&
                    (!operation.mutation.matches(saved) || saved.revision <= operation.revision)
                ) {
                    mutableState.value = current.copy(
                        busy = false, needsRead = true, message = EditorMessage.PROTOCOL,
                    )
                    return
                }
                val confirmed = unresolved?.matches(saved) == true
                val completedDraft = confirmed && unresolved?.selection != null &&
                    current.draft == unresolved.selection
                pending = null
                mutableState.value = current.copy(
                    saved = saved,
                    draft = if (completedDraft) null else current.draft,
                    busy = false,
                    needsRead = false,
                    message = when {
                        operation.mutation != null -> EditorMessage.SAVED
                        confirmed -> EditorMessage.RECONCILED
                        unresolved != null -> EditorMessage.NOT_RECONCILED
                        current.message == EditorMessage.CONFLICT -> EditorMessage.CONFLICT
                        else -> null
                    },
                )
                false
            }
        }
        if (readConflict) read()
    }
}
