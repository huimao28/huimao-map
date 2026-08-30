package com.huimao.map.wear

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class WearNavigationViewModel(context: Context) : ViewModel(), DataClient.OnDataChangedListener {
    private val _state = MutableStateFlow(WearNavigationState())
    val state: StateFlow<WearNavigationState> = _state.asStateFlow()
    private val dataClient = Wearable.getDataClient(context.applicationContext)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context.applicationContext)
    private val _phoneConnected = MutableStateFlow(false)
    val phoneConnected: StateFlow<Boolean> = _phoneConnected.asStateFlow()

    init {
        dataClient.addListener(this)
        refreshConnection()
    }

    fun refreshConnection() {
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes -> _phoneConnected.value = nodes.isNotEmpty() }
            .addOnFailureListener { _phoneConnected.value = false }
    }

    override fun onCleared() {
        dataClient.removeListener(this)
        super.onCleared()
    }

    override fun onDataChanged(buffer: DataEventBuffer) {
        refreshConnection()
        buffer.forEach { event ->
            if (event.dataItem.uri.path == "/huimao/navigation") {
                _state.value = WearNavigationState.from(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        }
    }
}
