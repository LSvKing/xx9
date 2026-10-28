package com.xx9.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xx9.app.data.ApiException
import com.xx9.app.data.HomeBlock
import com.xx9.app.data.Xx9Api
import kotlinx.coroutines.launch

/** 保活首页专题数据：返回首页不重刷 /api/home（真机反馈 #3）。 */
class HomeViewModel(private val api: Xx9Api) : ViewModel() {
    var loading by mutableStateOf(false)
        private set
    var err by mutableStateOf<String?>(null)
        private set
    var blocks by mutableStateOf<List<HomeBlock>>(emptyList())
        private set

    init {
        if (blocks.isEmpty()) refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        if (!force && blocks.isNotEmpty()) return
        if (loading) return
        loading = true
        err = null
        viewModelScope.launch {
            runCatching { api.home() }
                .onSuccess {
                    blocks = it
                    loading = false
                    err = null
                }
                .onFailure {
                    err = when (it) {
                        is ApiException -> if (it.code >= 500 || it.code == 0) "无法连接服务器" else "网络异常"
                        else -> "网络异常"
                    }
                    loading = false
                }
        }
    }

    class Factory(private val api: Xx9Api) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(api) as T
    }
}
