# 2. 一次对话如何运行

[返回目录](README.md) · 上一篇：[项目全貌](01-architecture.md) · 下一篇：[协议、流式输出和轨迹](03-protocols-and-streaming.md)

以“请计算 `(120+18)/3`”为例。模型可能直接回答；下面讨论它选择调用计算器的情况。

```mermaid
sequenceDiagram
    actor User as 用户
    participant Page as 浏览器
    participant Controller as ChatController
    participant Session as AgentSessions.Session
    participant Loop as AgentLoop
    participant Model as DeepSeek 客户端
    participant Tools as ToolRegistry
    User->>Page: 输入问题并发送
    Page->>Controller: POST /api/chat，携带会话 ID
    Controller->>Session: run(prompt, settings, sink)
    Session->>Loop: run(prompt, settings)
    Loop->>Loop: 保存 user 消息
    Loop->>Model: 历史消息 + 工具定义
    Model-->>Loop: calculator 调用及参数
    Loop->>Tools: dispatch(toolCall)
    Tools-->>Loop: 46
    Loop->>Loop: 保存 assistant 调用和 tool 结果
    Loop->>Model: 再次发送更新后的历史
    Model-->>Page: 文本分片经事件流显示
    Model-->>Loop: 完整回答，结束
```

## 第一步：页面建立会话并发送消息

[app.js](../src/main/resources/static/app.js) 加载时请求 `GET /api/session`，保存返回的 `sessionId`。发送消息时，浏览器请求 `POST /api/chat`，请求体包含 `prompt`、`model` 和 `reasoningLevel`，请求头 `X-Agent-Session` 用来找到对应的会话。

[ChatController.chat](../src/main/java/dev/learning/agent/app/chat/ChatController.java) 检查请求来源、会话 ID、消息长度和模型设置，然后取得会话执行权。相同会话已经有请求在运行时，新请求返回 HTTP 409。结果通过 `StreamingResponseBody` 逐行写回浏览器。

[AgentSessions](../src/main/java/dev/learning/agent/app/chat/AgentSessions.java) 为每个会话创建一个 `AgentLoop` 和工具注册表。会话中的 `currentSink` 把本轮的文字、工具、轨迹和确认事件送给当前浏览器连接。

## 第二步：AgentLoop 请求模型

打开 [AgentLoop.run](../src/main/java/dev/learning/agent/core/ReActRunner.java)，跟着这几个动作读：

1. 将用户消息追加到 `messages`，发出用户输入和模型设置的轨迹事件。
2. 将当前历史和工具定义交给 `ChatModel.stream(...)`。客户端一边把文本片段交给页面，一边组装完整的 `AssistantTurn`。
3. 记录模型完成或失败事件；模型返回思考内容时，也记录思考轨迹。
4. 如果模型返回工具调用，先保存 assistant 消息，再按顺序执行工具，把每个结果以 tool 消息保存，随后继续请求模型。
5. 如果没有工具调用且结束原因为 `stop`，保存最终回答并结束；异常结束或超出次数则报错。

每个用户轮次最多发起 8 次模型请求，数值在 `AgentSessions` 创建 `AgentLoop` 时传入。限制的是模型请求次数，一个请求可能包含多个工具调用。

## 第三步：工具是怎样执行的

[AgentTool](../src/main/java/dev/learning/agent/tool/AgentTool.java) 定义工具需要提供的两项能力：向模型描述工具，以及根据参数执行工具。[ToolRegistry](../src/main/java/dev/learning/agent/tool/ToolRegistry.java) 按名称保存工具，收到调用后查找并执行。

| 工具 | 参数示例 | 程序做什么 |
| --- | --- | --- |
| [calculator](../src/main/java/dev/learning/agent/tool/CalculatorTool.java) | `{"expression":"(120+18)/3"}` | 用解析器执行基本四则运算，返回 `46`。 |
| [search](../src/main/java/dev/learning/agent/tool/SearchTool.java) | `{"query":"Java 17"}` | 使用 Exa 搜索，返回标题、链接和摘要；未配置 key 时返回错误文本。 |
| [bash](../src/main/java/dev/learning/agent/tool/BashTool.java) | `{"command":"pwd"}` | 请求用户确认命令、运行命令，再请求用户确认是否分享输出。 |

工具的参数错误、未知工具和部分执行错误会转成文本结果，让模型有机会根据错误继续处理。模型 HTTP 请求或流解析失败则会结束当前用户轮次，并显示错误。

## 第四步：历史如何连接前后请求

| 顺序 | 角色 | 内容示例 |
| --- | --- | --- |
| 1 | `system` | 说明如何使用工具及遵守确认规则。 |
| 2 | `user` | 请计算 `(120+18)/3`。 |
| 3 | `assistant` | 调用 `calculator`，附带调用 ID 和参数。 |
| 4 | `tool` | 返回 `46`，用调用 ID 关联上一步。 |
| 5 | `assistant` | 根据工具结果回答用户。 |

这是 `AgentLoop` 使用的内部历史；各协议客户端会把它转换成接口需要的格式。开启思考时，模型实际返回的思考内容也会保存；Anthropic 客户端还保存原有内容块，以便后续请求回传。

下一篇解释同一份历史怎样适配三种接口：[协议、流式输出和轨迹](03-protocols-and-streaming.md)。
