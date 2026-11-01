# ReAct、ReWOO 与 Plan-and-Execute 方案研究记录

研究日期：2026-11-01（Asia/Shanghai） · [三模式技术方案](agent-execution-technical-design.md)

目的：确认现有 Java demo 怎样扩展为三种新对话执行模式，识别真正需要改动的边界。本文保存文献结论、当前代码事实和离线实测；实施接口与行为统一以[技术方案](agent-execution-technical-design.md)为准。

## 1. 查阅的一手资料

| 资料 | 本次核对内容 | 得出的实现约束 |
| --- | --- | --- |
| [ReWOO 原论文，2023，§2.1–2.2](https://arxiv.org/html/2305.18323v1) | Planner 一次生成带证据引用的计划，Worker 取证，Solver 合并计划与证据；减少重复上下文 | 保留 Plan/Work/Solve 职责与证据引用；串行执行也能先验证核心机制 |
| [LangChain：Plan-and-Execute Agents，2023-05-10](https://www.langchain.com/blog/plan-and-execute-agents) | 高层步骤与行动型执行器分离，初始版本未实现执行中修订 | Plan-and-Execute 的定义不等于“ReWOO 加重规划”；本项目单独定义高层步骤和局部执行 |
| [LangChain：Plan-and-Execute Agents，2024-02-13](https://www.langchain.com/blog/planning-agents) | 执行器可以用一个或多个工具完成步骤，执行后可重新规划或结束；同文对照 ReWOO | 选择带执行后检查的变体，并分别记录高层计划、低层调用与计划修订 |
| [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) | 模型提出调用、宿主执行；其中“Inserting Tool Calls Mid-Conversation”说明不同兼容接口的限制 | Worker 不需要伪造原生工具往返；使用普通数据携带证据更适合三协议共用 |
| [DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/) | Chat Completions 的 JSON 输出参数、提示约定与空白响应情况 | 格式参数之外仍需非空、完整响应和计划语义校验 |
| [DeepSeek Responses 兼容表](https://api-docs.deepseek.com/guides/responses_api/) | text.format 支持情况；无服务端会话；部分工具控制参数被忽略 | 不依赖 previous_response_id，不将服务端工具参数当作本地 Worker 预算 |
| [DeepSeek Anthropic 兼容表](https://api-docs.deepseek.com/guides/anthropic_api/) | output_config 仅支持 effort，不能直接假定兼容原生结构化输出能力 | 用提示 JSON 与本地校验作共同路径，单独验证协议增强功能 |
| [Anthropic：Building Effective Agents，2024](https://www.anthropic.com/research/building-effective-agents) | 从简单、可组合的流程开始，以任务效果判断是否增加复杂度 | 第一版先做串行静态计划，之后分别测量并行和重规划收益 |
| [Langfuse：What does a good trace look like?](https://langfuse.com/docs/observability/best-practices) | 单次用户任务作为 trace，各模型调用和工具操作单独记录、关联输入输出 | 沿用本地轨迹系统，补阶段、节点和稳定操作名；没有接入 Langfuse 服务 |
| [Spring Framework 6.2：CallableProcessingInterceptor](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/context/request/async/CallableProcessingInterceptor.html) | 异步 Callable 的超时、错误与完成钩子 | RunLease 清理必须覆盖执行回调尚未启动的路径 |
| [JDK 17：HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html) | 响应对象的可用不等于响应体已读完 | 已开始读取 SSE 后仍需注册流关闭动作，不能只依赖请求超时 |

以上资料在本次研究中通过网络读取。本文提炼机制与限制，没有下载外部实现或复用其他课程代码。产品兼容细节以研究日期为快照，实际编码时仍需对照请求格式并做验证。

## 2. 当前代码观察

| 观察点 | 证据位置 | 影响 |
| --- | --- | --- |
| 每个 Session 只构造一个 AgentLoop，且已有会话级运行锁 | [AgentSessions.java](../../src/main/java/dev/learning/agent/app/chat/AgentSessions.java) | 新对话首次接受消息时选择唯一 runner，模式绑定与运行锁获取需要原子完成 |
| 模型接口返回 AssistantTurn，支持独立文本回调 | [ChatModel.java](../../src/main/java/dev/learning/agent/core/ChatModel.java) | Planner 可缓冲，Solver 可流式输出；无需重新写三个 HTTP 客户端 |
| 模型设置和运行中的控件已有发送时快照与禁用逻辑 | [app.js](../../src/main/resources/static/app.js) | 模型设置继续每轮选择；模式按学习者要求增加对话级固定规则 |
| 设置文件只保存协议，启动时固定实际客户端 | [SettingsStore.java](../../src/main/java/dev/learning/agent/app/settings/SettingsStore.java) | 执行模式属于对话状态，不混入需要重启的全局设置 |
| 所有当前工具均使用具体的单字符串参数 | [CalculatorTool](../../src/main/java/dev/learning/agent/tool/CalculatorTool.java)、[SearchTool](../../src/main/java/dev/learning/agent/tool/SearchTool.java)、[BashTool](../../src/main/java/dev/learning/agent/tool/BashTool.java) | 首版可用明确的参数类型实现引用绑定，不需要通用 JSON 模板引擎 |
| 工具返回字符串；Bash 非零退出会体现在字符串中 | [ToolRegistry.java](../../src/main/java/dev/learning/agent/tool/ToolRegistry.java)、BashTool | 需要结构化状态，不能仅靠字符串是否以 error 开头判断依赖成功 |
| 工具结束轨迹当前统一 complete | [TraceEvent.java](../../src/main/java/dev/learning/agent/core/TraceEvent.java) | 状态展示要与真实执行结果对齐 |
| AgentLoop.run 返回 void，直接输出文本，模型轮次限额位于循环内部 | [AgentLoop.java](../../src/main/java/dev/learning/agent/core/AgentLoop.java) | P&E 需要提取能返回结构化步骤结果的 ReActEngine，并传入外层共享预算，而非为每个步骤直接调用旧 run |
| trace.js 只按模型和工具种类统计，未包含计划节点 | [trace.js](../../src/main/resources/static/trace.js) | 计划节点状态与实际工具调用计数需要分别管理 |

## 3. 离线探针：实际调用当前类

方法：使用 Maven 已生成测试报告中的测试 classpath，在临时目录编译一个同包 Java 探针。探针直接调用三个客户端的请求构造方法和现有工具注册表；没有创建网络连接，临时文件在实验后删除。

### 3.1 纯文本规划请求是否已可直接配置

对三个协议构造相同内容：system 要求 JSON 计划，user 提出计算问题，模型设置为默认值，传入空的工具列表。调用位置为：

```java
ChatRequest.forAgent(settings, messages, List.of());
new ResponsesClient(json, "test", "https://api.example.test")
        .request(messages, List.of(), settings);
new AnthropicClient(json, "test", "https://api.example.test")
        .request(messages, List.of(), settings);
```

将返回对象用项目的 ObjectMapper 序列化后检查字段，观测结果：

| 协议 | tools | tool_choice | 显式模型输出 token 上限 |
| --- | --- | --- | --- |
| Chat Completions | `[]` | `auto` | 无 |
| Responses | `[]` | `auto` | 无 |
| Anthropic Messages | `[]` | 未声明 | 无 |

这只是请求构造实验，没有测试服务端是否接受空工具列表。它说明现有统一入口还不能明确表达 Planner 所需的纯文本、格式偏好与输出预算；实现方案因此列出适配改动，不将“工具列表为空”视为全部工作已完成。

### 3.2 现有计算器能否直接被 Worker 复用

通过真实 ToolRegistry/CalculatorTool 调用两次，以第一次输出构造第二次参数；本探针手工串接结果，没有实现计划调度器。

| 调用 | 结果 |
| --- | --- |
| calculator，expression=`120+18` | `138` |
| calculator，expression=`138/3` | `46` |
| calculator，expression=`not-a-number` | `error: invalid tool arguments or operation: expected number at 0` |

可以确认工具能力可以直接复用；也确认失败以字符串暴露。它没有验证 Planner 质量、拓扑排序、ReWOO 流式流程或真实模型输出。

## 4. 当前回归基线

在项目目录执行：

```bash
mvn -q test
```

结果：10 个测试套件，48 项测试，0 failures、0 errors、0 skipped。覆盖当前循环、协议适配、工具行为、确认、设置及控制器等已有功能。

该结果来自方案初次研究时的实施前基线；后续扩展 Plan-and-Execute 文档没有重新运行 Maven，也没有新增运行代码或新模式测试。不能用这个基线声称新模式已经实现。基线运行时 JDK/Mockito 产生了动态加载 agent 的提示，测试正常完成。

## 5. 本次没有验证的部分

- 新增计划 JSON 能否在每种协议、每种推理等级稳定生成。
- 三协议纯文本配置和 JSON 格式增强参数的真实接口接受情况。
- Planner 修复成功率、Solver 证据忠实度和实际任务成功率。
- 真实 token、费用、延迟，以及串行或并行 ReWOO 对 ReAct 的收益。
- 拟议新对话模式选择、首次接受后锁定、只读模式标识、计划卡、硬截止和取消的浏览器行为。
- Plan-and-Execute 的步骤内 ReAct、高层验收、执行后控制、计划版本保留和双层预算。

本次没有调用 DeepSeek 或 Exa，没有修改运行代码、模型提示词或设置文件。上述项目全部进入[技术方案的验证计划](agent-execution-technical-design.md)。

## 6. 技术文档自身的检查

从技术方案提取共享 Java 契约，按 public 类型拆到临时目录，使用当前项目测试 classpath 编译，命令参数为 `javac --release 17 -Xlint:all -Werror`。32 个 public 类型编译通过，临时产物未写入项目源码。

这项检查只验证契约类型与 Java 17 语法，不验证实现、并发行为或三模式任务质量。方案中的 JSON/NDJSON 示例、相对链接、代码围栏与 SVG XML 另做格式检查；本次没有重新运行 Maven，也没有创建新的运行功能。
