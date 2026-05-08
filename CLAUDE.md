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
    <file name="ChatSession.kt">
      <role>主入口。会话级编排器：协调历史记录、流式输出、工具调用。</role>
      <state-machine>
        Idle → sendMessage() → 清理上一轮 → 发送请求 → 流式接收 → Complete
        → 若有待处理 toolCall → 等待工具结果 → 递归调用 sendMessage(null)
        → 否则 → 回调 onCompleted()
        reset() → 取消当前 Job → 清空 HistoryKeeper → 取消 ToolCallWaiter
      </state-machine>
      <key-types>内部持有 ChatClient, HistoryKeeper, ToolCallWaiter</key-types>
      <callback-interface>Callback: onConfigInvalid, onStarted, onUpdated, onContent, onError, onToolCall(suspend), onCompleted</callback-interface>
    </file>
    <file name="ChatClient.kt">
      <role>低级客户端。负责配置校验、请求构建。将 HTTP 委托给 ChatService。</role>
    </file>
    <file name="ChatPair.kt">
      <role>单轮对话数据模型（1 条用户消息 + N 条助手/工具消息）。</role>
      <state-enum>RoundState: Pending → Generating → Succeeded | Failed</state-enum>
    </file>
    <file name="Config.kt">
      <role>内部配置数据类，非公开 API。对外使用 ConfigBuilder DSL。</role>
    </file>
  </layer>

  <layer name="chat-stream" path="s3ss10n/src/main/java/com/niki914/s3ss10n/chat/">
    <file name="ChatBeans.kt">
      <role>流式管道领域事件类型。sealed AIContent、sealed ChatEvent。</role>
    </file>
    <file name="ChatService.kt">
      <role>内部。桥接 OkHttp 调用 → SseClient + SseToChatTransformLayer。</role>
    </file>
    <file name="SseToChatTransformLayer.kt">
      <role>内部。SseEvent → ChatEvent 转换。通过 ToolCallHandler 累积碎片化的 tool_call delta。</role>
    </file>
    <dir name="protocol/">
      <file name="ChatApiRequestBody.kt"><role>请求 JSON 模型</role></file>
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
    <file name="ToolManager.kt"><role>ToolModel 注册表 + 执行器。生成请求配置中的工具定义。</role></file>
    <file name="ToolModel.kt"><role>定义 LLM 可调用工具的抽象基类。子类实现 execInternal()。</role></file>
    <file name="ToolCallJsonTransformLayer.kt"><role>工具调用 → JSON 解析。为工具实现提供响应构建器。</role></file>
  </layer>

  <layer name="util" path="s3ss10n/src/main/java/com/niki914/s3ss10n/util/">
    <file name="ConfigBuilder.kt"><role>Config DSL 构造器。提供 socksProxy()、httpProxy() 便捷方法。</role></file>
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
  <note>Demo 应用。所有库集成模式以 DemoChatViewModel（ChatSession.Callback 实现）和 DemoToastModel（ToolModel 示例）为参考即可。其余 UI 代码为 Compose 常规写法，无特殊约束。</note>
  <file name="DemoChatViewModel.kt" path="app/src/main/java/com/niki914/demo/">
    <role>继承 ComposeMVIViewModel，实现 ChatSession.Callback。桥接库层 → MVI 状态。</role>
  </file>
  <file name="DemoToastModel.kt" path="app/src/main/java/com/niki914/demo/">
    <role>ToolModel 示例实现，演示工具调用集成模式。</role>
  </file>
</source-index>

<data-flow-trace>
  <trace id="send-message">
    ChatSession.sendMessage(userMsg)
    → cleanUpCurrWork()（取消前一个 Job）
    → HistoryKeeper.addUserMsg()
    → ChatClient.sendMessages(messages)
      → isConfigValid()? 否 → flowOf(Start, Error, Complete(false))
      → 是 → ChatService.newChat(requestBody)
        → Request.Builder（占位 URL，由 DynamicURLInterceptor 替换为 config.baseUrl）
        → OkHttp newCall → SseClient.execute()
          → SSE 逐行解析 → SseEvent Flow
          → SseToChatTransformLayer.transformEvent()
            → 对碎片化 tool_call delta 累积到 ToolCallHandler
            → 发出 ChatEvent.AI / ToolCallIntent / Error / Complete
    ← ChatSession sendMessage() 协程收集 Flow&lt;ChatEvent&gt;
      → ChatEvent.AI → HistoryKeeper.appendTextToLastAIMsg() → Callback.onContent()
      → ChatEvent.ToolCallIntent → HistoryKeeper.appendToolCallToLastAIMsg() → ToolCallWaiter.enqueue()
      → ChatEvent.Complete → toolCallWaiter 非空？
        → 是 → responseToolCalls() → HistoryKeeper.addToolResults() → 递归 sendMessage(null)
        → 否 → Callback.onCompleted()
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
