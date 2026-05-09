package com.niki914.s3ss10n.net

import kotlinx.coroutines.flow.Flow

interface HttpEngine {
    /**
     * 发起一次流式请求，返回 SSE 行的 Flow（每个元素是去掉 "data: " 前缀的 payload；终止标记 "[DONE]" 由 engine 内部识别后正常 close 流）
     */
    fun stream(request: HttpRequest): Flow<String>

    /**
     * 释放底层资源（连接池、线程池等）；幂等
     */
    fun close()
}
