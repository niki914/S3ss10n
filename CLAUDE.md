# AX Contract — S3ss10n

<project-identity>
  <name>S3ss10n</name>
  <artifact>com.github.niki914:s3ss10n</artifact>
  <type>Android 库 + Demo 应用</type>
  <description>基于 OkHttp + SSE 的流式 Chat Completions 客户端。提供会话级历史管理与 OpenAI 兼容的工具调用。</description>
</project-identity>

<module-map>
  <module name=":s3ss10n" publishable="true">
    <role>核心库。纯 Kotlin，无 Compose/UI 依赖。</role>
    <source-root>s3ss10n/src/main/java/com/niki914/s3ss10n/</source-root>
  </module>
  <module name=":composebase" publishable="false">
    <role>可复用 MVI ViewModel 基类（单文件），供 Compose UI 使用。</role>
    <source-root>composebase/src/main/java/com/niki914/composebase/</source-root>
  </module>
  <module name=":app" publishable="false">
    <role>Demo 应用。作为集成方的参考实现，无其他作用。</role>
    <source-root>app/src/main/java/com/niki914/demo/</source-root>
  </module>
</module-map>

<source-index module=":s3ss10n">

  <layer name="public-api" path="s3ss10n/src/main/java/com/niki914/s3ss10n/">
    <note>Phase 2 完成。ChatSession 直接实现 Session，SessionImpl 已删除，Callback 已消除。</note>

    <file name="Session.kt">
      <role>主入口 interface。开发者唯一接触的会话对象。</role>
      <contract>
        suspend fun send(text: String, onEvent: (SessionEvent) -> Unit = {})
        suspend fun getHistory(): List&lt;ChatPair&gt;
        suspend fun resetConversation()
        suspend fun close()
        companion.open(block: SessionConfig.() -> Unit): Session
      </contract>
      <note>update{} 暂未暴露（待 Phase 3）。</note>
    </file>

    <file name="SessionConfig.kt">
      <role>公开配置类（非 data class，待后续改为 data class + 扩展函数）。</role>
      <fields>endpoint, apiKey, model, systemPrompt, temperature, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds</fields>
      <dsls>hooks { }, localTools { }, mcp { }</dsls>
    </file>

    <file name="SessionEvent.kt">
      <role>send() 期间的细粒度事件流。</role>
      <sealed-types>
        RoundStarted(input), TextDelta(delta, fullText), ToolRunning/ToolSucceeded/ToolFailed,
        RoundCompleted(fullText), Error(stage, message, cause)
      </sealed-types>
      <enum>Stage: Transport, Parse, Tool, Session</enum>
    </file>

    <file name="ToolCallRequest.kt">
      <role>hooks {} 中接收的工具调用对象。sealed interface。</role>
      <methods>ok(contentJson), error(message, contentJson?), delegate()</methods>
      <impls>LocalToolCallRequest (使用 ToolManager), McpToolCallRequest (占位)</impls>
    </file>

    <file name="ToolCallKind.kt">
      <role>工具来源区分。sealed interface: Local (data object), Mcp(serverName) (data class)。</role>
    </file>

    <file name="LocalToolRegistry.kt">
      <role>localTools {} DSL 类型。</role>
      <types>LocalToolRegistry (interface), LocalToolConfig, LocalToolProperty, ToolValueType (enum)</types>
      <dsl-props>string/integer/number/boolean/object_/array/rawJsonSchema</dsl-props>
    </file>

    <file name="McpTypes.kt">
      <role>MCP 占位类型。编译通过，无真实行为。</role>
      <types>McpRegistry (interface), McpServerConfig, McpTransport (sealed: Http)</types>
    </file>

    <!-- 以下为旧 API，仍存在于内部但不再推荐直接使用 -->

    <file name="ChatSession.kt">
      <role>Session 的内部实现。直接实现 Session 接口，持有 ChatClient、HistoryKeeper、ToolCallWaiter 协调流式对话和工具调用。</role>
      <constructor>新增 constructor(config: SessionConfig)，由 Session.open{} 调用。</constructor>
      <state-machine>
        send(text, onEvent) → 清空 textAccumulator → applyConfig → sendMessage(text)
        → cleanUpCurrWork() → HistoryKeeper.addUserMsg() → ChatClient.sendMessages()
        → 收集 ChatEvent:
          → Start → emit RoundStarted
          → AI(Text) → 累积 textAccumulator → emit TextDelta
          → ToolCallIntent → enqueue to ToolCallWaiter → handleToolCall() → hooks{} 调度 → emit ToolRunning/Succeeded/Failed
          → Complete → 若有 toolCall 则 responseToolCalls() 递归 sendMessage(null)
                     → 否则 emit RoundCompleted
          → Error → emit Error (按 ConfigInvalidException 区分 stage)
        reset() → 取消当前 Job → 清空 HistoryKeeper → 取消 ToolCallWaiter
      </state-machine>
      <note>已移除 Callback 接口和 updateConfig() 公开方法。applyConfig() 为内部方法。</note>
    </file>

    <file name="ChatClient.kt">
      <role>低级客户端。负责配置校验、请求构建。将 HTTP 委托给 ChatService。</role>
    </file>

    <file name="ChatPair.kt">
      <role>单轮对话数据模型（1 条用户消息 + N 条助手/工具消息）。Session.getHistory() 的返回类型。</role>
      <state-enum>RoundState: Pending → Generating → Succeeded | Failed</state-enum>
    </file>

    <file name="Config.kt">
      <role>内部配置数据类（internal）。SessionConfig DSL 结果经 ConfigBuilder 映射到此类型。</role>
    </file>
  </layer>

  <layer name="chat-stream" path="s3ss10n/src/main/java/com/niki914/s3ss10n/chat/">
    <file name="ChatBeans.kt">
      <role>内部流式管道事件。sealed AIContent、sealed ChatEvent。ChatSession 内部消费，直接映射为 SessionEvent 后发射给用户 onEvent。</role>
    </file>
    <file name="ChatService.kt">
      <role>内部。桥接 OkHttp 调用 → SseClient + SseToChatTransformLayer。</role>
    </file>
    <file name="SseToChatTransformLayer.kt">
      <role>内部。SseEvent → ChatEvent 转换。通过 ToolCallHandler 累积碎片化的 tool_call delta。</role>
    </file>
    <dir name="protocol/">
      <file name="ChatApiRequestBody.kt"><role>请求 JSON 模型（含 temperature）</role></file>
      <file name="ChatApiResponseFrame.kt"><role>响应 JSON 模型</role></file>
      <file name="RequestModel.kt"><role>ToolDefinition, FunctionTool, FunctionParameters, PropertyDefinition</role></file>
      <file name="ResponseModel.kt"><role>ToolCall, FunctionCall, Choice, Delta</role></file>
      <dir name="beans/">
        <file name="Message.kt"><role>sealed Message: System, User, Assistant, Tool</role></file>
      </dir>
    </dir>
  </layer>

  <layer name="network" path="s3ss10n/src/main/java/com/niki914/s3ss10n/net/">
    <file name="OkhttpClientManager.kt">
      <role>OkHttpClient 单例工厂。装配 3 个动态拦截器 + DynamicProxySelector。</role>
    </file>
    <dir name="sse/">
      <file name="SseClient.kt"><role>SSE 引擎：阻塞式 OkHttp call.execute() + 逐行解析 → Flow&lt;SseEvent&gt;</role></file>
      <file name="SseBeans.kt"><role>SseEvent 密封体系 + SSE 协议解析器</role></file>
    </dir>
  </layer>

  <layer name="tool-calling" path="s3ss10n/src/main/java/com/niki914/s3ss10n/toolbase/">
    <file name="ToolManager.kt"><role>ToolModel 注册表 + 执行器。LocalToolCallRequest.delegate() 内部使用。</role></file>
    <file name="ToolModel.kt"><role>定义 LLM 可调用工具的抽象基类（旧 API，内部保留）。</role></file>
    <file name="ToolCallJsonTransformLayer.kt"><role>工具调用 → JSON 解析。为工具实现提供响应构建器。</role></file>
  </layer>

  <layer name="util" path="s3ss10n/src/main/java/com/niki914/s3ss10n/util/">
    <file name="ConfigBuilder.kt"><role>内部。SessionConfig → Config 的桥梁。通过 ChatClient.updateConfig() 应用配置。保留 socksProxy()、httpProxy()。</role></file>
    <file name="ConfigHolder.kt"><role>基于 AtomicReference 的线程安全 Config 容器。</role></file>
    <file name="HistoryKeeper.kt"><role>线程安全 ChatPair 列表。Mutex 保护。支持向最新 Assistant 消息增量追加 text/toolCall。</role></file>
    <file name="ToolCallHandler.kt"><role>累积流式 tool_call JSON 片段，在收到完成信号时发出完整 ToolCall。</role></file>
    <file name="ToolCallWaiter.kt"><role>流式过程中入队 ToolCall，全部收齐后并发等待结果，供下一轮注入。</role></file>
    <file name="DynamicProxySelector.kt"><role>从 ConfigHolder 读取代理配置的 ProxySelector。</role></file>
    <file name="Gson.kt"><role>单例 Gson 实例。</role></file>
    <dir name="interceptors/">
      <file name="DynamicURLInterceptor.kt"><role>请求时动态替换占位 URL → config.baseUrl</role></file>
      <file name="ChatApiInterceptor.kt"><role>注入 Authorization + Content-Type 头</role></file>
      <file name="DynamicTimeoutInterceptor.kt"><role>按请求应用 config 中的超时配置</role></file>
    </dir>
  </layer>

</source-index>

<source-index module=":composebase">
  <file name="ComposeMVIViewModel.kt" path="composebase/src/main/java/com/niki914/composebase/">
    <role>抽象 MVI ViewModel 基类。</role>
    <contract>
      泛型参数：Intent, State, Effect（均由使用者定义）
      State 通过 StateFlow 暴露（uiStateFlow）
      一次性副作用通过 SharedFlow 暴露（uiEffect，容量=1）
      sendIntent(Intent) → Channel → handleIntent() 协程
    </contract>
  </file>
</source-index>

<source-index module=":app">
  <note>Demo 应用。使用新 Session API（Session.open {} + SessionEvent 回调）。已删除 DemoToastModel（工具改为 localTools DSL 内联定义 + hooks {} 处理）。</note>
  <file name="DemoChatViewModel.kt" path="app/src/main/java/com/niki914/demo/">
    <role>继承 ComposeMVIViewModel。通过 Session.open {} 创建会话，send() 的 onEvent 回调映射到 MVI 状态。</role>
    <key-pattern>ChatState.pairs 通过 Session.getHistory() 轮询刷新。</key-pattern>
  </file>
</source-index>

<data-flow-trace>
  <trace id="send-message-phase2">
    Session.send(text, onEvent)
    → ChatSession: 清空 textAccumulator, 记录 currentInput, applyConfig()
    → sendMessage(text)
      → cleanUpCurrWork()
      → HistoryKeeper.addUserMsg()
      → ChatClient.sendMessages(messages)
        → ChatService.newChat(requestBody)
          → SseClient.execute() → SseEvent Flow
          → SseToChatTransformLayer → ChatEvent Flow
      ← ChatSession 收集 ChatEvent + 直接发射 SessionEvent:
        → ChatEvent.Start → emit RoundStarted (不重置 textAccumulator)
        → ChatEvent.AI(Text) → 累积 textAccumulator → emit TextDelta
        → ChatEvent.ToolCallIntent → enqueue ToolCallWaiter → handleToolCall()
          → 构建 ToolCallRequest → emit ToolRunning
          → 调用 hooks{} block → emit ToolSucceeded/ToolFailed → 返回 Message.Tool
        → ChatEvent.Complete → 若有 toolCall → responseToolCalls() → 递归 sendMessage(null)
          → textAccumulator 跨轮继续累积（不清空）
          → 否则 → emit RoundCompleted(fullText)
        → ChatEvent.Error → emit Error (stage 按异常类型区分)
  </trace>
  <trace id="known-issue">
    <issue>update{} 未实现：Session 接口无 update 方法，SessionConfig 构造后不可变。</issue>
    <issue>MCP 仅占位：McpTypes.kt 编译通过但 delegate() 始终返回 "not implemented"。</issue>
  </trace>
</data-flow-trace>

<build-config-index>
  <file name="build.gradle.kts" path="."><role>根构建文件。仅声明插件（apply false）。</role></file>
  <file name="settings.gradle.kts" path="."><role>模块引入 + 仓库配置。</role></file>
  <file name="gradle/libs.versions.toml" path="gradle/"><role>所有依赖版本、库、插件的集中声明。</role></file>
  <file name="app/build.gradle.kts" path="app/"><role>compileSdk/minSdk/targetSdk。Compose 开关。</role></file>
  <file name="s3ss10n/build.gradle.kts" path="s3ss10n/"><role>库模块 + maven-publish 配置。</role></file>
  <file name="composebase/build.gradle.kts" path="composebase/"><role>库模块。</role></file>
</build-config-index>