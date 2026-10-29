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

/**
 * 首页专题数据：保活不闪烁（真机反馈 #3），但允许过期后静默刷新。
 * TTL 与服务端 _home SWR 窗口一致（300s）：回首页/切回 tab 时过期才重拉，
 * 刷新期间现有 blocks 保持显示，成功后整表替换 —— 无 Loading 闪屏。
 */
private const val HOME_STALE_MS = 300_000L

class HomeViewModel(private val api: Xx9Api) : ViewModel() {
    var loading by mutableStateOf(false)
        private set
    var err by mutableStateOf<String?>(null)
        private set
    var blocks by mutableStateOf<List<HomeBlock>>(emptyList())
        private set

    private var loadedAt = 0L

    init {
        refresh(force = false)
    }

    /** 数据为空或超过 TTL 才刷新；新鲜数据直接跳过（真机反馈 #3 保活语义保留）。 */
    fun refreshIfStale() {
        if (blocks.isEmpty() || System.currentTimeMillis() - loadedAt > HOME_STALE_MS) {
            refresh(force = true)
        }
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
                    loadedAt = System.currentTimeMillis()
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
