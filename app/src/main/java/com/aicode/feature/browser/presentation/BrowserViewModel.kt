package com.aicode.feature.browser.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.agent.domain.container.PortMappingError
import com.aicode.feature.agent.domain.container.SshPortMappingManager
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val browserManager: BrowserManager,
    private val portMappings: SshPortMappingManager
) : ViewModel() {
    val portMappingState = portMappings.state
    private val messages = Channel<Int>(Channel.BUFFERED)
    val portMappingMessages = messages.receiveAsFlow()

    fun addPortMapping(remotePort: String, localPort: String) {
        val remote = parsePortMappingPort(remotePort)
        val local = if (localPort.isEmpty()) null else parsePortMappingPort(localPort)
        if (remote == null || (localPort.isNotEmpty() && local == null)) {
            messages.trySend(R.string.port_mapping_error_invalid_port)
            return
        }
        portMappings.add(remote, local)
    }

    fun closePortMapping(id: String) {
        portMappings.close(id)
    }

    fun portMappingAddress(id: String): String? = portMappings.address(id).also { address ->
        if (address == null) messages.trySend(R.string.port_mapping_error_connection_changed)
    }

    fun copyPortMapping(id: String, copy: (String) -> Unit) {
        portMappingAddress(id)?.let(copy)
    }

    fun openPortMapping(id: String) {
        // 与 navigate 的 Main 调度器一致，避免地址复核后再排队切换线程。
        viewModelScope.launch(Dispatchers.Main) {
            val address = portMappingAddress(id) ?: return@launch
            try {
                browserManager.navigate(address)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                messages.trySend(R.string.port_mapping_error_connection_failed)
            }
        }
    }
}

internal fun parsePortMappingPort(value: String): Int? = value
    .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toIntOrNull()
    ?.takeIf { it in 1..65535 }

@StringRes
internal fun PortMappingError.messageRes(): Int = when (this) {
    PortMappingError.INVALID_PORT -> R.string.port_mapping_error_invalid_port
    PortMappingError.LOCAL_MODE -> R.string.port_mapping_error_local_mode
    PortMappingError.NOT_CONNECTED -> R.string.port_mapping_error_not_connected
    PortMappingError.PORT_IN_USE -> R.string.port_mapping_error_port_in_use
    PortMappingError.TARGET_UNREACHABLE -> R.string.port_mapping_error_target_unreachable
    PortMappingError.CONNECTION_FAILED -> R.string.port_mapping_error_connection_failed
    PortMappingError.LIMIT_REACHED -> R.string.port_mapping_error_limit_reached
    PortMappingError.CONNECTION_CHANGED -> R.string.port_mapping_error_connection_changed
    PortMappingError.LISTEN_FAILED -> R.string.port_mapping_error_listen_failed
}
