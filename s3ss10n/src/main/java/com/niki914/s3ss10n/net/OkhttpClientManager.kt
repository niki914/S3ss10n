package com.niki914.s3ss10n.net

import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.util.DynamicProxySelector
import com.niki914.s3ss10n.util.interceptors.ChatApiInterceptor
import com.niki914.s3ss10n.util.interceptors.DynamicTimeoutInterceptor
import com.niki914.s3ss10n.util.interceptors.DynamicURLInterceptor
import com.zephyr.log.logE
import com.zephyr.provider.TAG
import okhttp3.OkHttpClient

/**
 * OkHttp 客户端管理器
 * 职责：
 * 1. 维护一个支持动态配置（BaseUrl, Timeout, Proxy）的 OkHttpClient 单例
 * 2. 提供更新网络配置的接口
 * 3. 向业务层提供配置好的 OkHttpClient 实例
 */
internal class OkhttpClientManager(
    private val configSupplier: () -> SessionConfig
) {

    /**
     * 向模块内部暴露配置好的 OkHttpClient 实例
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addAllInterceptors()
            .proxySelector(DynamicProxySelector(configSupplier))
            .build()
    }

    private fun OkHttpClient.Builder.addAllInterceptors(): OkHttpClient.Builder {
        val timeoutInterceptor =
            DynamicTimeoutInterceptor(
                getConnectTimeout = {
                    configSupplier().connectTimeoutSeconds.toInt()
                },
                getReadTimeout = {
                    configSupplier().readTimeoutSeconds.toInt()
                },
                getWriteTimeout = {
                    configSupplier().writeTimeoutSeconds.toInt()
                }
            )

        val urlInterceptor =
            DynamicURLInterceptor(
                getUrl = {
                    configSupplier().endpoint
                }
            )

        val apiInterceptor = ChatApiInterceptor(
            getHeaders = {
                mapOf(
                    "Authorization" to "Bearer ${configSupplier().apiKey}",
                    "Content-Type" to "application/json"
                )
            }
        )

        return this
            .addInterceptor(timeoutInterceptor)
            .addInterceptor(urlInterceptor)
            .addInterceptor(apiInterceptor)
    }
}
