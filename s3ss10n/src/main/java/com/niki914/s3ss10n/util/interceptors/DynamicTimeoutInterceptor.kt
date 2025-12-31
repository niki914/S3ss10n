package com.niki914.s3ss10n.util.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 动态超时
 */
internal class DynamicTimeoutInterceptor(
    private val getConnectTimeout: () -> Int,
    private val getReadTimeout: () -> Int,
    private val getWriteTimeout: () -> Int,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain
            .withConnectTimeout(getConnectTimeout(), TimeUnit.SECONDS)
            .withReadTimeout(getReadTimeout(), TimeUnit.SECONDS)
            .withWriteTimeout(getWriteTimeout(), TimeUnit.SECONDS)
            .proceed(chain.request())
    }
}