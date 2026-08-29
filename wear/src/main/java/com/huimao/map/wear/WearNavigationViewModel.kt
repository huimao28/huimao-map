package com.huimao.map.wear

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class WearNavigationViewModel(context: Context) : ViewModel(), DataClient.OnDataChangedListener {
    private val _state = MutableStateFlow(WearNavigationState())
    val state: StateFlow<WearNavigationState> = _state.asStateFlow()
    private val dataClient = Wearable.getDataClient(context.applicationContext)

    init { dataClient.addListener(this) }

    override fun onCleared() {
        dataClient.removeListener(this)
        super.onCleared()
    }

    override fun onDataChanged(buffer: DataEventBuffer) {
        buffer.forEach { event ->
            if (event.dataItem.uri.path == "/huimao/navigation") {
                _state.value = WearNavigationState.from(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        }
    }
}
