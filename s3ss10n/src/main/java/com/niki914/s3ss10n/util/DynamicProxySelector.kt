package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.SessionConfig
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal class DynamicProxySelector(
    private val configSupplier: () -> SessionConfig
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        // T6 之前临时返回 NO_PROXY，代理 DSL 已删除
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // 可以添加日志或故障转移逻辑
    }
}