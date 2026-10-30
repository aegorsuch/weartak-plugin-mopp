package com.weartak.plugin.mopp.host

import android.os.BadParcelableException
import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import com.weartak.plugin.mopp.EditorMessage
import com.weartak.plugin.mopp.HostTransportException
import com.weartak.plugin.mopp.MoppEditor
import com.weartak.plugin.mopp.Posture
import com.weartak.plugin.mopp.ResultCode
import com.weartak.plugin.sdk.mopp.aidl.IMoppPpeControlV1
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultCode
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultParcel
import com.weartak.plugin.sdk.mopp.model.MoppPpeStateParcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdkMoppHostTest {
    @Test fun readsEveryWireValueAndNeverSavedWithoutInventingTimestamp() {
        val control = MoppControlStub()
        val host = SdkMoppHost(control)
        (listOf(null) + Posture.entries).forEach { posture ->
            control.state = MoppPpeStateParcel(
                7, posture?.wireValue, null, true, posture != null, null,
            )
            val result = host.read()
            assertTrue(result.accepted)
            assertEquals(posture, result.state!!.selection)
            assertNull(result.state!!.savedAtEpochMillis)
        }
    }

    @Test fun forwardsExactSaveRevisionMutationAndSelection() {
        val control = MoppControlStub()
        SdkMoppHost(control).save(7, "mutation-1", Posture.NONE)
        assertEquals(Triple(7L, "mutation-1", "none"), control.lastSave)
    }

    @Test fun generatedProxyRoundTripsTypedStateAndMutationArguments() {
        val control = MoppControlStub()
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                control.transact(code, data, reply, flags)
        }
        val host = SdkMoppHost(IMoppPpeControlV1.Stub.asInterface(remote))
        val state = host.read().state!!
        assertEquals(7L, state.revision)
        assertEquals(Posture.MOPP2, state.selection)
        assertNull(state.savedAtEpochMillis)
        host.save(7, "proxy-save", Posture.MOPP4)
        assertEquals(Triple(7L, "proxy-save", "4"), control.lastSave)
        host.setEnabled(7, "proxy-enable", false)
        assertEquals(Triple(7L, "proxy-enable", false), control.lastEnable)
    }

    @Test fun forwardsEnableWithoutSelectionOrTimestampRewrite() {
        val control = MoppControlStub()
        val result = SdkMoppHost(control).setEnabled(7, "enable-1", false)
        assertEquals(Triple(7L, "enable-1", false), control.lastEnable)
        assertEquals(Posture.MOPP2, result.state!!.selection)
        assertNull(result.state!!.savedAtEpochMillis)
    }

    @Test fun mapsAllTypedRejectionsWithoutClaimingSavedState() {
        val control = MoppControlStub()
        for ((code, expected) in listOf(
            MoppPpeResultCode.REVISION_CONFLICT to ResultCode.REVISION_CONFLICT,
            MoppPpeResultCode.INVALID_REQUEST to ResultCode.INVALID_REQUEST,
            MoppPpeResultCode.UNAUTHORIZED to ResultCode.UNAUTHORIZED,
            MoppPpeResultCode.INACTIVE_SESSION to ResultCode.INACTIVE_SESSION,
            MoppPpeResultCode.PERSISTENCE_FAILED to ResultCode.PERSISTENCE_FAILED,
        )) {
            control.result = MoppPpeResultParcel(false, code, null)
            val result = SdkMoppHost(control).read()
            assertFalse(result.accepted)
            assertEquals(expected, result.code)
            assertNull(result.state)
        }
    }

    @Test fun malformedWireValueOrUnknownCodeRequiresProtocolRecovery() {
        val control = MoppControlStub()
        val editor = MoppEditor()
        editor.attach(SdkMoppHost(control))
        control.state = MoppPpeStateParcel(7, "unknown", null, true, true, null)
        editor.read()
        assertEquals(EditorMessage.PROTOCOL, editor.state.value.message)
        assertFalse(editor.state.value.canSelect)
        control.result = MoppPpeResultParcel(false, 999, null)
        editor.read()
        assertEquals(EditorMessage.PROTOCOL, editor.state.value.message)
        assertNull(editor.state.value.saved)
    }

    @Test fun remoteFailureRemainsUnconfirmedAndSecurityFailureRevokesAccess() {
        val control = MoppControlStub()
        val host = SdkMoppHost(control)
        control.failure = RemoteException("connection lost")
        assertThrows(HostTransportException::class.java) { host.read() }
        control.failure = SecurityException("revoked")
        assertEquals(ResultCode.UNAUTHORIZED, host.read().code)
    }

    @Test fun malformedParcelSurfacesProtocolErrorInsteadOfCrashingEditor() {
        val control = MoppControlStub().apply {
            failure = BadParcelableException("invalid snapshot")
        }
        val editor = MoppEditor()
        editor.attach(SdkMoppHost(control))
        editor.read()
        assertEquals(EditorMessage.PROTOCOL, editor.state.value.message)
        assertFalse(editor.state.value.canSelect)
    }

    @Test fun typedImmediateSaveReopensPersistedSelectionWithoutAnotherMutation() {
        val control = MoppControlStub().apply { commitWrites = true }
        val editor = MoppEditor { "save-1" }
        editor.attach(SdkMoppHost(control))
        editor.read()
        editor.save(Posture.MOPP4)
        assertEquals(EditorMessage.SAVED, editor.state.value.message)
        assertEquals(Posture.MOPP4, editor.state.value.saved!!.selection)
        assertEquals(123456789L, editor.state.value.saved!!.savedAtEpochMillis)
        editor.disconnect()
        val reopened = MoppEditor()
        reopened.attach(SdkMoppHost(control))
        reopened.read()
        assertEquals(Posture.MOPP4, reopened.state.value.saved!!.selection)
        assertEquals(8L, reopened.state.value.saved!!.revision)
        assertEquals(Triple(7L, "save-1", "4"), control.lastSave)
    }

    @Test fun typedRejectedSaveRetainsSelectionAndRequiresReadBeforeRetry() {
        val control = MoppControlStub()
        val editor = MoppEditor()
        editor.attach(SdkMoppHost(control))
        editor.read()
        control.result = MoppPpeResultParcel(false, MoppPpeResultCode.PERSISTENCE_FAILED, control.state)
        editor.save(Posture.MOPP4)
        assertEquals(EditorMessage.PERSISTENCE, editor.state.value.message)
        assertEquals(Posture.MOPP2, editor.state.value.saved!!.selection)
        assertFalse(editor.state.value.canSelect)
    }
}

internal class MoppControlStub : IMoppPpeControlV1.Stub() {
    var state = MoppPpeStateParcel(7, "2", null, true, true, null)
    var result: MoppPpeResultParcel? = null
    var failure: Exception? = null
    var commitWrites = false
    var beforeRead: (() -> Unit)? = null
    var lastSave: Triple<Long, String, String>? = null
    var lastEnable: Triple<Long, String, Boolean>? = null

    override fun read(): MoppPpeResultParcel {
        beforeRead?.invoke()
        failure?.let { throw it }
        return result ?: MoppPpeResultParcel(true, MoppPpeResultCode.OK, state)
    }

    override fun save(expectedRevision: Long, mutationId: String, selection: String): MoppPpeResultParcel {
        lastSave = Triple(expectedRevision, mutationId, selection)
        if (commitWrites) {
            state = MoppPpeStateParcel(
                expectedRevision + 1, selection, 123456789L, state.enabled,
                state.enabled, mutationId,
            )
        }
        return read()
    }

    override fun setEnabled(expectedRevision: Long, mutationId: String, enabled: Boolean): MoppPpeResultParcel {
        lastEnable = Triple(expectedRevision, mutationId, enabled)
        if (commitWrites) {
            state = MoppPpeStateParcel(
                expectedRevision + 1, state.selection, state.savedAtEpochMillis,
                enabled, enabled && state.selection != null, mutationId,
            )
        }
        return read()
    }
}
