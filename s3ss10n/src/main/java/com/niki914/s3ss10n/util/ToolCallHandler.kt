package com.niki914.s3ss10n.util

import com.niki914.s3ss10n.chat.protocol.FunctionCall
import com.niki914.s3ss10n.chat.protocol.ToolCall

/**
 * 封装工具调用处理逻辑的类。因为 openai 的请求喜欢把函数调用拆开来传，非常麻烦
 *
 * 负责累积流式传输的工具调用片段，并提供已完成的工具调用意图
 *
 * 假设
 * 1. function.name 总会在该 ToolCall 的第一个 delta 中完整出现。
 * 2. 同一个流中，不会出现在一个工具调用（Tool Call）的参数还没有完全接收完的时候，又开始一个新的工具调用。
 * 3. 当 ToolCallDelta 的 ID 为空时，它属于当前正在处理的 ToolCall。
 * 4. 就算是无参的调用也至少会构成 {} 空 json 体
 */
internal class ToolCallHandler {

    /**
     * 存储当前正在进行的工具调用及其累积信息
     * 由于 ID 可能为空，只维护一个当前活跃的 ToolCallAccumulator
     */
    private var currentActiveToolCall: ToolCallAccumulator? = null

    /**
     * 内部数据类，用于存储一个工具调用的所有累积信息。
     * @param initialToolCall 首次接收到的包含 id, type, name 等信息的 ToolCall 对象。
     * @param argumentsBuilder 用于累积 arguments 字符串的 StringBuilder。
     */
    private data class ToolCallAccumulator(
        val initialToolCall: ToolCall,
        val argumentsBuilder: StringBuilder
    )

    /**
     * 处理来自流的单个 ToolCall 片段，可能返回构建好的工具调用意图
     */
    fun push(toolCallDelta: ToolCall): ToolCall? {
        val callId: String? = toolCallDelta.id
        val argumentsChunk: String? = toolCallDelta.function?.arguments

        if (callId != null) {
            // 如果存在 ID，意味着一个新的工具调用开始
            // 此时，无论之前是否有未完成的工具调用，都将其“完成”或舍弃。
            // 确保在开始新的工具调用之前，尝试完成并发出当前的工具调用（如果存在且未完成）
            // 这是为了处理假设3：就算是无参的调用也至少会构成 {} 空 json 体，
            // 并且防止因为流的结束而导致最后一个ToolCall没有被完整处理。
            currentActiveToolCall = ToolCallAccumulator(toolCallDelta, StringBuilder())
        }

        // 统一处理参数追加逻辑，无论 ID 是否为空
        // 关键：只在这里追加一次 argumentsChunk
        argumentsChunk?.let {
            currentActiveToolCall?.argumentsBuilder?.append(it)
        }

        // 每次收到 chunk 后，都尝试检查是否可以完成并返回工具调用
        return checkIfCurrCompleted()
    }

    /**
     * 检查当前活跃的工具调用是否已完成解析，如果完成则通过 FlowCollector 发出。
     *
     * 每次收到新的 arguments chunk 或流结束时都应调用此方法。
     */
    private fun checkIfCurrCompleted(): ToolCall? {
        currentActiveToolCall?.let { accumulator ->
            val fullArguments = accumulator.argumentsBuilder.toString()

            if (fullArguments.isEmpty() || !isJson(fullArguments)) {
                return null
            }

            val completedToolCall = ToolCall(
                id = accumulator.initialToolCall.id,
                type = accumulator.initialToolCall.type,
                function = FunctionCall(
                    name = accumulator.initialToolCall.function?.name,
                    arguments = fullArguments
                )
            )

            // 成功解析并发送后，清除当前活跃的工具调用，等待下一个
            clear()

            return completedToolCall
        }

        return null
    }

    private fun isJson(string: String): Boolean {
        try {
            gson.fromJson(string, Map::class.java)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun clear() {
        currentActiveToolCall = null
    }
}