package com.niki914.s3ss10n.net

import com.niki914.s3ss10n.Config
import com.niki914.s3ss10n.util.ConfigBuilder
import com.niki914.s3ss10n.util.ConfigHolder
import com.niki914.s3ss10n.util.DynamicProxySelector
import com.niki914.s3ss10n.util.gson
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
internal class OkhttpClientManager {

    private val configHolder = ConfigHolder {
//        baseUrl = ""
    }

    val config: Config
        get() = configHolder.config

    fun updateConfig(block: ConfigBuilder.() -> Unit) {
        configHolder.update(block)
    }

    fun updateConfig(config: Config) {
        configHolder.update(config)
    }

    /**
     * 向模块内部暴露配置好的 OkHttpClient 实例
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addAllInterceptors()
            .proxySelector(DynamicProxySelector(configHolder))
            .build()
    }

    private fun OkHttpClient.Builder.addAllInterceptors(): OkHttpClient.Builder {
        val timeoutInterceptor =
            DynamicTimeoutInterceptor(
                getConnectTimeout = {
                    configHolder.config.connectTimeout.toInt()
                },
                getReadTimeout = {
                    configHolder.config.readTimeout.toInt()
                },
                getWriteTimeout = {
                    configHolder.config.writeTimeout.toInt()
                }
            )

        val urlInterceptor =
            DynamicURLInterceptor(
                getUrl = {
                    configHolder.config.baseUrl
                }
            )

        val apiInterceptor = ChatApiInterceptor(
            getHeaders = {
                mapOf(
                    "Authorization" to "Bearer ${configHolder.config.apiKey}",
                    "Content-Type" to "application/json"
                )
            }
        )

        return apply {
            configHolder.config.interceptors.forEach { interceptor ->
                addInterceptor(interceptor)
            }
        }
            .addInterceptor(timeoutInterceptor)
            .addInterceptor(urlInterceptor)
            .addInterceptor(apiInterceptor)
            .addNetworkInterceptor {
                val requestBodyJson = gson.toJson(it.request().body)
                logE(TAG, requestBodyJson)
                it.proceed(it.request())
            }
    }
}