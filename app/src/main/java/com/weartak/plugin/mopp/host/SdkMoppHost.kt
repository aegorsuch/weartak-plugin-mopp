package com.weartak.plugin.mopp.host

import android.os.BadParcelableException
import android.os.RemoteException
import com.weartak.plugin.mopp.HostResult
import com.weartak.plugin.mopp.HostTransportException
import com.weartak.plugin.mopp.MoppHost
import com.weartak.plugin.mopp.Posture
import com.weartak.plugin.mopp.ResultCode
import com.weartak.plugin.mopp.SavedPosture
import com.weartak.plugin.sdk.mopp.aidl.IMoppPpeControlV1
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultCode
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultParcel

class SdkMoppHost(private val control: IMoppPpeControlV1) : MoppHost {
    override fun read(): HostResult = call { control.read() }

    override fun save(expectedRevision: Long, mutationId: String, selection: Posture): HostResult =
        call { control.save(expectedRevision, mutationId, selection.wireValue) }

    override fun setEnabled(expectedRevision: Long, mutationId: String, enabled: Boolean): HostResult =
        call { control.setEnabled(expectedRevision, mutationId, enabled) }

    private fun call(operation: () -> MoppPpeResultParcel?): HostResult {
        val result = try {
            operation()
        } catch (error: RemoteException) {
            throw HostTransportException(error)
        } catch (error: SecurityException) {
            return HostResult(false, ResultCode.UNAUTHORIZED, null)
        } catch (error: BadParcelableException) {
            return protocolError()
        } ?: return protocolError()
        val state = result.state
        val selection = state?.selection?.let { wireValue ->
            Posture.entries.find { it.wireValue == wireValue } ?: return protocolError()
        }
        return HostResult(
            result.accepted,
            when (result.code) {
                MoppPpeResultCode.OK -> ResultCode.OK
                MoppPpeResultCode.REVISION_CONFLICT -> ResultCode.REVISION_CONFLICT
                MoppPpeResultCode.INVALID_REQUEST -> ResultCode.INVALID_REQUEST
                MoppPpeResultCode.UNAUTHORIZED -> ResultCode.UNAUTHORIZED
                MoppPpeResultCode.INACTIVE_SESSION -> ResultCode.INACTIVE_SESSION
                MoppPpeResultCode.PERSISTENCE_FAILED -> ResultCode.PERSISTENCE_FAILED
                else -> ResultCode.PROTOCOL_ERROR
            },
            state?.let {
                SavedPosture(
                    it.revision, selection, it.savedAtEpochMillis, it.enabled,
                    it.contributing, it.lastMutationId,
                )
            },
        )
    }

    private fun protocolError() = HostResult(false, ResultCode.PROTOCOL_ERROR, null)
}
