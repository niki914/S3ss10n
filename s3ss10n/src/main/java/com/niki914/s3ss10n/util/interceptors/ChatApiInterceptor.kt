package com.niki914.s3ss10n.util.interceptors

import okhttp3.Interceptor
import okhttp3.Response

internal class ChatApiInterceptor(
    private val getHeaders: () -> Map<String, String>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = originalRequest
            .newBuilder()
            .apply {
                val headers = getHeaders()
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        return chain.proceed(newRequest)
    }
}