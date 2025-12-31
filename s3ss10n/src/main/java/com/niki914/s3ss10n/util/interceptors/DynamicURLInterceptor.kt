package com.niki914.s3ss10n.util.interceptors

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 动态 URL
 */
internal class DynamicURLInterceptor(
    private val getUrl: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = originalRequest.newBuilder()
            .url(getUrl())
            .build()

        return chain.proceed(newRequest)
    }
}