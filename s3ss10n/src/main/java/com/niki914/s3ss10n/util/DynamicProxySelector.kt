package com.niki914.s3ss10n.util

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal class DynamicProxySelector(
    private val configHolder: ConfigHolder
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        // 从持有者获取当前配置的代理
        val currentProxy = configHolder.config.proxy

        // 如果配置了代理，则返回该代理；否则返回NO_PROXY，表示直连
        return listOf(currentProxy ?: Proxy.NO_PROXY)
    }

    // connectFailed方法通常用于复杂的代理失败重试逻辑，这里我们简单实现
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // 可以添加日志或故障转移逻辑
    }
}