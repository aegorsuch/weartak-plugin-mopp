package com.weartak.plugin.mopp

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MoppEditorTest {
    private class Host : MoppHost {
        var saved = SavedPosture(7, Posture.MOPP2, null, true, true, null)
        var reads = 0
        var saves = 0
        var toggles = 0
        var saveFailure: ResultCode? = null
        var loseReply = false
        var duringSave: (() -> Unit)? = null
        var readResult: HostResult? = null

        override fun read(): HostResult {
            reads++
            return readResult ?: HostResult(true, ResultCode.OK, saved)
        }

        override fun save(expectedRevision: Long, mutationId: String, selection: Posture): HostResult {
            saves++
            assertEquals(saved.revision, expectedRevision)
            duringSave?.invoke()
            saveFailure?.let { return HostResult(false, it, saved) }
            saved = saved.copy(
                revision = saved.revision + 1, selection = selection,
                savedAtEpochMillis = 123456789L, lastMutationId = mutationId,
            )
            if (loseReply) throw HostTransportException(IllegalStateException("Lost reply"))
            return HostResult(true, ResultCode.OK, saved)
        }

        override fun setEnabled(expectedRevision: Long, mutationId: String, enabled: Boolean): HostResult {
            toggles++
            assertEquals(saved.revision, expectedRevision)
            saved = saved.copy(
                revision = saved.revision + 1, enabled = enabled,
                contributing = enabled && saved.selection != null, lastMutationId = mutationId,
            )
            return HostResult(true, ResultCode.OK, saved)
        }
    }

    private fun connected(host: Host = Host()): Pair<MoppEditor, Host> {
        val editor = MoppEditor()
        editor.attach(host)
        editor.read()
        return editor to host
    }

    @Test fun noneAndZeroAreDistinctFromNeverSaved() {
        assertNotEquals(Posture.NONE, Posture.MOPP0)
        assertEquals(listOf("none", "0", "1", "2", "3", "4"), Posture.entries.map { it.wireValue })
        val host = Host().apply {
            saved = saved.copy(selection = null, savedAtEpochMillis = null, contributing = false)
        }
        val (editor, _) = connected(host)
        assertNull(editor.state.value.saved!!.selection)
        editor.save(Posture.NONE)
        assertEquals(Posture.NONE, host.saved.selection)
        editor.save(Posture.MOPP0)
        assertEquals(Posture.MOPP0, host.saved.selection)
    }

    @Test fun readsAndClosePreserveLegacyTimestampWithoutWriting() {
        val (editor, host) = connected()
        editor.cancelDraft()
        editor.read()
        assertNull(editor.state.value.saved!!.savedAtEpochMillis)
        assertNull(editor.state.value.draft)
        assertEquals(Posture.MOPP2, host.saved.selection)
        assertEquals(0, host.saves)
        assertEquals(0, host.toggles)
    }

    @Test fun saveEverySelectionUsesHostRevisionAndTimestamp() {
        val (editor, host) = connected()
        Posture.entries.forEachIndexed { index, selection ->
            assertEquals(index, host.saves)
            editor.save(selection)
            assertEquals(selection, editor.state.value.saved!!.selection)
            assertEquals(123456789L, editor.state.value.saved!!.savedAtEpochMillis)
            assertEquals(8L + index, editor.state.value.saved!!.revision)
            assertNull(editor.state.value.draft)
        }
    }

    @Test fun sameValueTapIsNotSkipped() {
        val (editor, host) = connected()
        editor.save(Posture.MOPP2)
        assertEquals(1, host.saves)
        assertEquals(8L, host.saved.revision)
        assertEquals(123456789L, host.saved.savedAtEpochMillis)
    }

    @Test fun disableRetainsSavedSelectionAndTimeWithoutSaving() {
        val (editor, host) = connected()
        editor.setEnabled(false)
        assertFalse(host.saved.enabled)
        assertFalse(host.saved.contributing)
        assertEquals(Posture.MOPP2, host.saved.selection)
        assertNull(host.saved.savedAtEpochMillis)
        assertNull(editor.state.value.draft)
        assertEquals(0, host.saves)
        editor.setEnabled(false)
        assertEquals(1, host.toggles)
        assertFalse(editor.state.value.canSelect)
        assertTrue(editor.state.value.canToggle)
        editor.save(Posture.MOPP4)
        assertEquals(0, host.saves)
        editor.setEnabled(true)
        assertTrue(editor.state.value.canSelect)
    }

    @Test fun conflictReadsAgainAndPreservesDraftWithoutResaving() {
        val (editor, host) = connected()
        host.saveFailure = ResultCode.REVISION_CONFLICT
        host.duringSave = { host.saved = host.saved.copy(revision = 8, selection = Posture.MOPP3) }
        editor.save(Posture.MOPP4)
        assertEquals(2, host.reads)
        assertEquals(1, host.saves)
        assertEquals(Posture.MOPP3, editor.state.value.saved!!.selection)
        assertEquals(Posture.MOPP4, editor.state.value.draft)
        assertEquals(EditorMessage.CONFLICT, editor.state.value.message)
    }

    @Test fun lostReplyBlocksWritesUntilReadConfirmsMutation() {
        val (editor, host) = connected()
        host.loseReply = true
        editor.save(Posture.NONE)
        assertEquals(EditorMessage.TRANSPORT, editor.state.value.message)
        assertTrue(editor.state.value.needsRead)
        editor.save(Posture.MOPP4)
        editor.setEnabled(false)
        assertEquals(1, host.saves)
        assertEquals(0, host.toggles)
        editor.read()
        assertEquals(EditorMessage.RECONCILED, editor.state.value.message)
        assertNull(editor.state.value.draft)
        assertEquals(Posture.NONE, editor.state.value.saved!!.selection)
        assertEquals(1, host.saves)
    }

    @Test fun supersededLostReplyDoesNotClaimSuccessOrReplay() {
        val (editor, host) = connected()
        host.loseReply = true
        editor.save(Posture.MOPP4)
        host.saved = host.saved.copy(
            revision = 9, selection = Posture.MOPP1, lastMutationId = "another-mutation",
        )
        editor.read()
        assertEquals(EditorMessage.NOT_RECONCILED, editor.state.value.message)
        assertEquals(Posture.MOPP4, editor.state.value.draft)
        assertEquals(Posture.MOPP1, editor.state.value.saved!!.selection)
        assertEquals(1, host.saves)
    }

    @Test fun tapDuringSaveCannotReplacePendingSelection() {
        val (editor, host) = connected()
        host.duringSave = { editor.save(Posture.MOPP4) }
        editor.save(Posture.MOPP0)
        assertEquals(Posture.MOPP0, editor.state.value.saved!!.selection)
        assertNull(editor.state.value.draft)
        assertEquals(1, host.saves)
    }

    @Test fun disconnectClearsDraftAndNeverReplaysAcrossBinding() {
        val (editor, oldHost) = connected()
        editor.disconnect()
        editor.save(Posture.MOPP4)
        assertNull(editor.state.value.saved)
        assertNull(editor.state.value.draft)
        assertEquals(0, oldHost.saves)
        val newHost = Host().apply { saved = saved.copy(selection = Posture.NONE) }
        editor.attach(newHost)
        editor.save(Posture.MOPP4)
        editor.read()
        assertEquals(Posture.NONE, editor.state.value.saved!!.selection)
        assertEquals(0, newHost.saves)
    }

    @Test fun staleInFlightReplyCannotRestoreAnOldBinding() {
        val (editor, oldHost) = connected()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        oldHost.duringSave = {
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        val thread = Thread { editor.save(Posture.MOPP4) }
        thread.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            editor.disconnect()
            val newHost = Host().apply { saved = saved.copy(selection = Posture.MOPP0) }
            editor.attach(newHost)
            editor.read()
        } finally {
            release.countDown()
            thread.join(5000)
        }
        assertFalse(thread.isAlive)
        assertEquals(Posture.MOPP0, editor.state.value.saved!!.selection)
        assertNull(editor.state.value.draft)
    }

    @Test fun revokedAndInactiveResultsClearAuthoritativeState() {
        for (code in listOf(ResultCode.UNAUTHORIZED, ResultCode.INACTIVE_SESSION)) {
            val (editor, host) = connected()
            host.saveFailure = code
            editor.save(Posture.MOPP4)
            assertEquals(Availability.DISCONNECTED, editor.state.value.availability)
            assertEquals(EditorMessage.DENIED, editor.state.value.message)
            assertNull(editor.state.value.saved)
            assertFalse(editor.state.value.canSelect)
        }
    }

    @Test fun rejectedSaveDoesNotClearDraftAndRequiresFreshRead() {
        for ((code, message) in listOf(
            ResultCode.PERSISTENCE_FAILED to EditorMessage.PERSISTENCE,
            ResultCode.INVALID_REQUEST to EditorMessage.INVALID,
        )) {
            val (editor, host) = connected()
            host.saveFailure = code
            editor.save(Posture.MOPP4)
            assertEquals(message, editor.state.value.message)
            assertEquals(Posture.MOPP4, editor.state.value.draft)
            assertTrue(editor.state.value.needsRead)
            assertEquals(Posture.MOPP2, editor.state.value.saved!!.selection)
        }
    }

    @Test fun malformedOrRegressingStateNeverEnablesSaving() {
        val invalidStates = listOf(
            null,
            SavedPosture(-1, Posture.NONE, null, true, true, null),
            SavedPosture(6, Posture.NONE, null, true, true, null),
            SavedPosture(8, null, 50, true, false, null),
            SavedPosture(8, Posture.NONE, null, false, true, null),
        )
        invalidStates.forEach { invalid ->
            val (editor, host) = connected()
            host.readResult = HostResult(true, ResultCode.OK, invalid)
            editor.read()
            assertEquals(EditorMessage.PROTOCOL, editor.state.value.message)
            assertFalse(editor.state.value.canSelect)
        }
    }

    @Test fun incompatibleHostCannotReceiveDraftsOrWrites() {
        val (editor, host) = connected()
        editor.disconnect(Availability.UPDATE_WEARTAK)
        editor.save(Posture.MOPP4)
        editor.setEnabled(false)
        assertNull(editor.state.value.draft)
        assertEquals(0, host.saves)
        assertEquals(0, host.toggles)
    }

    @Test fun selectionRemainsAuthoritativeUntilPersistenceAcknowledgement() {
        val (editor, host) = connected()
        host.duringSave = {
            assertEquals(Posture.MOPP2, editor.state.value.saved!!.selection)
            assertEquals(Posture.MOPP4, editor.state.value.draft)
            assertTrue(editor.state.value.busy)
            assertFalse(editor.state.value.canSelect)
        }
        editor.save(Posture.MOPP4)
        assertEquals(Posture.MOPP4, editor.state.value.saved!!.selection)
        assertTrue(editor.state.value.canSelect)
    }

    @Test fun reopeningSeedsFromPersistedHostWithoutAnotherSave() {
        val (editor, host) = connected()
        editor.save(Posture.MOPP1)
        editor.cancelDraft()
        editor.disconnect()
        val reopened = MoppEditor()
        reopened.attach(host)
        assertFalse(reopened.state.value.canSelect)
        reopened.read()
        assertEquals(Posture.MOPP1, reopened.state.value.saved!!.selection)
        assertEquals(123456789L, reopened.state.value.saved!!.savedAtEpochMillis)
        assertEquals(1, host.saves)
    }
}
