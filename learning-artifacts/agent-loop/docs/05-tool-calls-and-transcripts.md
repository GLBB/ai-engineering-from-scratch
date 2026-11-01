# 5. 工具调用上限、终止条件和对话记录

[返回目录](README.md) · 上一篇：[动手运行和调试](04-run-and-debug.md) · 下一篇：[用同一段对话理解三种协议](06-three-protocols-by-example.md)

本文对应 Agent Loop 练习中的三个问题：三个调用只执行两个、怎样结束循环，以及工具结果怎样关联调用。协议说明于 2026-11-01 对照官方文档核对。离线实验检查项目请求适配器的行为；真实 API 实验另行记录 DeepSeek 三种接口的 HTTP 状态与模型回复。

## 先预测，再运行

同一个 `calculator` 工具收到三个调用：

| 调用 ID | 参数 | 手算结果 |
| --- | --- | --- |
| `call_a` | `1+2` | `3` |
| `call_b` | `4*5` | `20` |
| `call_c` | `6-1` | `5` |

先预测：只执行前两个时，第三个调用在历史中是什么状态？如果结果按 `c → b → a` 返回，能否仅凭工具名称或位置匹配？

在 `learning-artifacts/agent-loop/` 下运行：

```bash
mvn -Dtest=ToolTranscriptExperimentTest,ReActRunnerTest test
```

[ToolTranscriptExperimentTest](../src/test/java/dev/learning/agent/deepseek/ToolTranscriptExperimentTest.java) 用三个场景分别调用 Chat Completions、Responses 和 Anthropic 请求适配器，共九个实验。它将实际序列化的请求写到被 Git 忽略的 `target/tool-transcript-experiment/`，可以直接用编辑器比较 `missing-chat.json`、`capped-chat.json` 和 `reversed-chat.json` 等文件。

| 场景 | 已执行的调用 | 回传结果的 ID | 尚未得到结果的调用 |
| --- | --- | --- | --- |
| `missing`：截掉第三个结果 | a、b | a、b | c |
| `capped`：第三个返回未执行错误 | a、b | a、b、c | 无 |
| `reversed`：模拟倒序完成 | c、b、a | c、b、a | 无 |

## 只执行两个，到底坏在哪里

模型的 assistant 消息仍然声明了三个调用，宿主却只提供两个结果。第三个调用没有成功、失败或拒绝的记录，下一次请求的工具往返还没有完成。对于要求完整配对的接口，这种历史会被拒绝。Anthropic 文档明确要求工具结果紧跟调用消息，并说明缺少对应结果的错误。[官方结果格式要求](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)

本地实验还发现一个边界：当前三个适配器只转换消息格式，都能把这份不完整历史序列化出来。JSON 可以生成，不代表服务端会接受。离线实验本身没有验证服务端响应；下面的真实 API 实验确认，这份缺少结果的历史会被 DeepSeek 三种接口拒绝。

如果运行策略允许执行前两个，可以保留原来的三个调用，为第三个补上真实的拒绝结果：

```json
{"role":"tool","tool_call_id":"call_c","content":"error: not executed; max_tool_calls_per_turn=2"}
```

这样限制了实际执行次数，同时明确告诉模型第三个没有执行，不能声称得到了 `5`。在 Anthropic 原生格式中，未执行的调用应以对应的 `tool_result` 返回，并设置 `is_error: true`。[并行调用与跳过执行](https://platform.claude.com/docs/en/agents-and-tools/tool-use/parallel-tool-use)

实验中的 Anthropic 适配器仍沿用项目现有的字符串错误观察，尚未建模 `is_error`；因此该实验验证的是结果配对，没有验证结构化错误标记。实验也没有修改实际 `AgentLoop` 的执行策略，应用目前仍按顺序执行模型返回的全部调用。

另一种策略是在执行任何工具之前拒绝整批调用。无论选哪种策略，都要明确区分“本批执行次数上限”和“整个用户轮次的总预算”：模型可能在下次请求里重新发起刚才未执行的调用。预算耗尽应报告未完成，不能当作成功。

## DeepSeek 真实 API 实测

2026-11-01 01:11–01:14（Asia/Shanghai），使用环境变量中的 DeepSeek key，向 `https://api.deepseek.com` 发起了 12 次请求。模型为 `deepseek-flash`，关闭思考，使用非流式响应，每个请求的输出上限为 512 tokens。

每种协议先请求一次，让真实模型同时生成三个 `calculator` 调用，参数依次为 `1+2`、`4*5`、`6-1`。保留这条模型实际返回的调用消息，再从同一个历史分出三组后续请求。三组使用相同工具定义、系统提示和模型设置，只改变工具结果。

| 后续请求 | Chat Completions | Responses | Anthropic 兼容接口 |
| --- | --- | --- | --- |
| `full`：执行三次，返回三个结果 | HTTP 200 | HTTP 200 | HTTP 200 |
| `missing`：只执行前两次，仅返回两个结果 | HTTP 400 | HTTP 400 | HTTP 400 |
| `skipped`：只执行前两次，第三个返回未执行错误 | HTTP 200 | HTTP 200 | HTTP 200 |

三次缺失结果请求都返回 `invalid_request_error`，错误消息的关键部分如下；调用 ID 和 request ID 省略：

```text
Chat Completions:
An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'.
(insufficient tool messages following tool_calls message)

Responses:
No tool output found for tool call <第三个调用的 ID>.

Anthropic 兼容接口:
`tool_use` ids were found without `tool_result` blocks immediately after: <第三个调用的 ID>.
Each `tool_use` block must have a corresponding `tool_result` block in the next message.
```

完整结果组中，模型都正常汇总 `3`、`20` 和 `5`。未执行错误组中，第三个结果为 `error: not executed; max_tool_calls_per_turn=2`，Anthropic 格式还设置了 `is_error: true`。DeepSeek 当前兼容文档说明 `is_error` 会被忽略，因此这次实测不能证明结构化错误标记生效，错误详情仍通过 content 传递。三种接口的模型都明确报告第三项未执行，并提出可以另行重试；没有把第三项声称为已成功计算。本次没有自动跟进重试。[DeepSeek Anthropic 兼容说明](https://api-docs.deepseek.com/guides/anthropic_api/)

这次实测说明：在上述条件下，只回传两个结果会让下一次请求直接失败；第三个调用即使不执行，也需要有明确的对应结果。这些结果适用于本次 DeepSeek 兼容接口，未实测 OpenAI、Anthropic 原生服务或 Bedrock，也未覆盖其他模型、思考模式和流式响应。

可复现脚本为 [experiments/deepseek_tool_limit.py](../experiments/deepseek_tool_limit.py)，仅依赖 Python 标准库。它从环境读取 key，只向官方 DeepSeek 域名发送固定算术数据，不读取或上传项目文件。运行命令会实际调用模型；每种协议最多尝试三次取得符合条件的调用消息，再发起三次对照请求。

```bash
# 已在环境中设置 DEEPSEEK_API_KEY 后，在本项目目录执行。
python3 experiments/deepseek_tool_limit.py
# 只复现 Chat Completions：
python3 experiments/deepseek_tool_limit.py --protocol chat
```

请求体、响应体和 `summary.json` 写入 `target/deepseek-tool-limit-live/<UTC时间>/`，不记录认证头，也不提交生成记录。本次记录目录为 `target/deepseek-tool-limit-live/20261031T171123075834Z/`；目录时间为 UTC，换算到上海时间是 2026-11-01。

## 没有工具调用，能证明任务完成吗

[ReActRunnerTest](../src/test/java/dev/learning/agent/core/ReActRunnerTest.java) 中的 `noToolCallsEndsTheLoopEvenWhenTheTaskWasNotPerformed` 让模拟模型返回“接下来会计算”，工具调用列表为空，结束原因为 `stop`。当前循环只调用模型一次，随后正常返回；计算并未发生。

这说明正常结束一次模型生成，与完成用户任务，是两个不同的判断。当前循环已经检查结束原因，因此 `length` 或流中断不会仅因没有工具调用就被当作成功；它仍然缺少业务完成条件。

| 终止策略 | 容易出现的误判 | 宿主需要补充的检查 |
| --- | --- | --- |
| 无工具调用且模型正常停止就结束 | 把计划、反问或未完成回答当成任务完成 | 是否有最终回答，任务需要的证据是否齐全 |
| 必须调用 `finish` 才允许结束 | 模型可能过早调用 `finish`，也可能一直不调用 | 校验完成证据；缺少 `finish` 不算成功；保留轮次上限 |

显式 `finish` 能让结束意图成为可检查的动作，更容易防住“偶然没有工具调用就退出”的缺陷。安全性来自宿主的完成检查，而不是工具名称。可以让 `finish` 提交结果与证据引用，再由程序核验。

如果 `finish` 与普通工具出现在同一批次，也不能看到 `finish` 就立刻退出，忽略其他尚未处理的调用。应先检查是否有未解决的工具请求，并制定拒绝或完成该批次的规则。

## 同一次工具往返，在四种 API 中长什么样

下面是“计算 `1+2`”的对话拼接视图，省略 `model`、工具定义和 HTTP 头。初次请求只有用户问题；调用由模型返回，工具结果由宿主补入，最终回答由下一次模型请求生成。这些示例不是把未来回答预填进第一次请求。

| 格式 | 模型发起调用 | 宿主返回结果 | 用来配对的字段 |
| --- | --- | --- | --- |
| Chat Completions | assistant 的 `tool_calls[]` | `role: tool` 消息 | 调用的 `id` 对应结果的 `tool_call_id` |
| Responses | `function_call` item | `function_call_output` item | 两边的 `call_id` |
| Anthropic Messages | assistant 内容中的 `tool_use` 块 | user 内容中的 `tool_result` 块 | 调用的 `id` 对应结果的 `tool_use_id` |
| Bedrock Converse | assistant 内容中的 `toolUse` 块 | user 内容中的 `toolResult` 块 | 两边的 `toolUseId` |

### Chat Completions：工具结果有单独的角色

```json
{
  "messages": [
    {"role":"user","content":"计算 1+2"},
    {"role":"assistant","content":null,"tool_calls":[
      {"id":"call_a","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\"1+2\"}"}}
    ]},
    {"role":"tool","tool_call_id":"call_a","content":"3"},
    {"role":"assistant","content":"结果是 3。"}
  ]
}
```

注意 `arguments` 是包含 JSON 的字符串。对应项目的 `ChatMessage` 和 `ChatRequest`，由 `ChatCompletionsClient` 发送。[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)

### Responses：调用与结果都是独立 item

```json
{
  "input": [
    {"role":"user","content":"计算 1+2"},
    {"type":"function_call","id":"fc_a","call_id":"call_a","name":"calculator","arguments":"{\"expression\":\"1+2\"}"},
    {"type":"function_call_output","call_id":"call_a","output":"3"},
    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"结果是 3。"}]}
  ]
}
```

`fc_a` 是 item 的 ID，`call_a` 才是工具往返的关联 ID；结果不能误用 `fc_a`。使用推理模型时，手动维护历史还需要保留相关 reasoning items，而不能仅保存可见的最终文本。[OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling)、[保留 reasoning items](https://developers.openai.com/api/docs/guides/reasoning#keeping-reasoning-items-in-context)

项目的 `ResponsesClient` 连接的是 DeepSeek 兼容接口，不能把它当作 OpenAI 全部能力的实现。DeepSeek 不支持 `previous_response_id`，需要回放完整历史；它支持纯文本 reasoning 内容，不支持 OpenAI 的 `summary` 和 `encrypted_content`。其 `max_tool_calls` 与 `parallel_tool_calls` 参数目前也被忽略，不能替代宿主执行预算。[DeepSeek 兼容性说明](https://api-docs.deepseek.com/guides/responses_api/)

### Anthropic Messages：工具结果放在 user 内容块中

```json
{
  "messages": [
    {"role":"user","content":[{"type":"text","text":"计算 1+2"}]},
    {"role":"assistant","content":[
      {"type":"tool_use","id":"call_a","name":"calculator","input":{"expression":"1+2"}}
    ]},
    {"role":"user","content":[
      {"type":"tool_result","tool_use_id":"call_a","content":"3"}
    ]},
    {"role":"assistant","content":[{"type":"text","text":"结果是 3。"}]}
  ]
}
```

这里的 `user` 是协议承载工具结果的角色，不表示人手工输入了“3”。多个客户端工具的结果合并到紧接调用消息的一条 user 消息中；内容块里的 ID 负责配对。工具参数 `input` 是 JSON 对象。[Anthropic 结果格式](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)、[并行工具格式](https://platform.claude.com/docs/en/agents-and-tools/tool-use/parallel-tool-use)

### Bedrock Converse：使用 toolUseId

```json
{
  "messages": [
    {"role":"user","content":[{"text":"计算 1+2"}]},
    {"role":"assistant","content":[
      {"toolUse":{"toolUseId":"call_a","name":"calculator","input":{"expression":"1+2"}}}
    ]},
    {"role":"user","content":[
      {"toolResult":{"toolUseId":"call_a","content":[{"text":"3"}],"status":"success"}}
    ]},
    {"role":"assistant","content":[{"text":"结果是 3。"}]}
  ]
}
```

这是 Converse 的格式；通过 Bedrock InvokeModel 访问具体模型时，应遵循该模型的请求格式。项目目前没有 Bedrock 客户端，此处只做文档对照。[Bedrock 客户端工具调用](https://docs.aws.amazon.com/bedrock/latest/userguide/tool-use-client-side.html)

## 为什么必须按调用 ID 匹配

三个调用使用相同的工具名 `calculator`，因此工具名只能说明调用哪种能力，不能说明是哪一次调用。倒序返回时，按位置拼接会把 `5` 配给 `1+2`，把 `3` 配给 `6-1`；按 ID 拼接仍得到 `call_a → 3`、`call_b → 20`、`call_c → 5`。

配对检查还可以发现漏回、重复回和陌生 ID。ID 本身不会执行这些检查，也不保证幂等执行；宿主仍需维护状态。并行执行的完成顺序可以不同，但发送给模型前仍要满足各 API 的消息分组和顺序要求。

本项目已在 `ToolCall.id` 和 `ChatMessage.toolCallId` 中保存关联 ID，三个适配器分别映射到各自协议字段。`AgentLoop` 生成的 `tool-<requestId>-<序号>` 用于界面轨迹，它不是模型的工具调用 ID，不能拿来填写协议结果的关联字段。
