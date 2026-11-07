# 格式检查与执行时机

Java 编码按阿里手册，具体项目约束见 [Java 项目规范入口](java-development-standard.md)。
以下命令均在 `learning-artifacts/agent-loop/` 下运行，需要 Python 3、Git、Maven 和 JDK 17+。
首次运行需要下载 Maven 工具依赖，之后按本机缓存使用。

## 工具分工

| 工具 | 作用 | 边界 |
| --- | --- | --- |
| Spotless 3.11.0 + Eclipse JDT 4.26 | 使用阿里官方 XML，统一四空格、120 列换行、方法分隔等排版 | `apply` 会改写文件；不代替业务重构，不自动插入缺失的大括号 |
| Maven Checkstyle 3.6.0 | 检查缩进、120 列行长、空行、大括号、空白、基本命名和导入等 | 本地阿里风格规则子集，不是阿里官方发布的完整 Checkstyle 规则集 |
| JUnit / Java 编译器 | 检查类型、可编译性和程序行为 | 不能由格式检查替代 |

格式来源、许可证及两项现代 JDT 补充设置记录在
[配置来源](../../config/alibaba/README.md)。Checkstyle 使用插件随附的 9.3 引擎。
禁止切换为 Google 默认规则、使用忽略失败参数或添加大范围豁免来获得通过。

Checkstyle 的 120 列检查豁免 package/import 和独占一行的 URL。
不可拆分的长字符串或文本块仍可能报错，不能为了消除报错改变其运行结果；
应按真实用途调整数据存放位置，确需例外时先记录具体范围和理由，再实现最小豁免。
方法职责、完整手册要求及业务质量仍按约定评审。

## 日常开发：只处理本次改动

```bash
python3 scripts/java_quality.py apply
python3 scripts/java_quality.py check
mvn test
```

默认范围包括当前项目相对 `HEAD` 的暂存、未暂存以及未跟踪 Java 源码。
检查整个选中的文件，包含生产和测试代码；删除、忽略及非 Java 文件不参与。
命令打印实际文件列表；没有匹配文件时明确报告“未运行检查”，不能冒充检查通过。
`check` 分别执行 Spotless 和 Checkstyle，即使前一个失败也会执行后一个，并返回失败退出码。
`apply` 只格式化，不替你修复命名、缺少大括号等 Checkstyle 问题。
格式化后检查 diff，再处理剩余问题；不要反复盲目运行 `apply` 期待消除所有错误。

这些命令通过参数列表调用 Maven，不使用 shell 拼接文件名。遇到不能安全表达为 Maven
过滤器的文件名会失败，不能扩大范围到整个项目或静默跳过。

## 提交评审前：包括分支上已提交的代码

```bash
git fetch origin main
python3 scripts/java_quality.py check --base origin/main
mvn test
```

`--base` 使用指定基线与 `HEAD` 的 merge-base，同时包含工作区变更。
不能只检查干净工作区，否则已经提交的违规代码会漏掉。
基线不存在或历史不足时命令失败，需补全 Git 历史，不能退回空范围并报告通过。
需要修复该范围时显式运行 `apply --base origin/main` 并审查差异。
暂存后又格式化了代码，必须检查并重新暂存实际修复，避免提交旧内容。

## 全量审计与构建入口

```bash
python3 scripts/java_quality.py check --all
mvn -Pquality verify
```

`--all` 审计所有生产与测试 Java 文件。`quality` profile 在 `verify` 阶段绑定
Spotless 与 Checkstyle，并执行正常 Maven 构建和测试；任一检查失败，构建失败。
普通 `mvn test` 仍不执行格式检查，便于开发中快速验证行为。
现有 Java 源码和测试已完成本地规则迁移，交付前应确保全量检查通过；自动检查不代表完整覆盖阿里手册。

当前已提供 CI 可调用的入口，尚未修改仓库 CI 工作流，也未安装 Git hook。
CI 应检出完整 Git 历史，运行 `mvn -Pquality verify` 全量阻塞；
本地仍可使用增量 `check --base` 检查分支改动。CI 只运行检查，不运行 `apply`。
本地 hook 可以提示，但可被跳过，不能替代 CI 和受保护分支的必需检查。

## 什么时候检查

| 时机 | 执行内容 | 理由 |
| --- | --- | --- |
| 正在编辑、语法尚未完整 | 不自动强制全量检查 | 避免中断思路和无意义解析错误 |
| 完成一个可解析的逻辑改动 | 所改文件 `apply`，再 `check` | 及时发现排版和编码问题，限制改写范围 |
| 准备提交或交付 | 分支基线 `check --base`、测试、最终 diff 自查 | 覆盖已提交代码及格式调整后的实际内容 |
| PR / CI | 增量检查与测试；迁移完成后全量 quality profile | 独立于 Agent 是否记得执行命令，失败时阻塞合并 |
| 修改 formatter、Checkstyle、工具版本或范围脚本 | 配置测试、全量审计、必要的行为回归 | 发现工具之间的冲突及新增历史违规，不只检查配置文件本身 |
| 仅修改文档 | 文档链接和结构检查 | 不为没有变化的 Java 反复运行全量格式检查 |

纯格式整理应独立于业务修改；格式化前先确认工作区，不能覆盖其他人的工作。
可在 IDE 保存时做当前文件格式化，但不要求每敲一行或每次 Agent 工具调用都运行 Maven。

## IntelliJ IDEA

Eclipse 是 Maven 使用的格式化引擎，不要求安装 Eclipse IDE。
IDEA 支持导入 Eclipse XML Profile：进入 `Settings → Editor → Code Style → Java`，
在 Scheme 操作中选择 `Import Scheme → Eclipse XML Profile`，导入
`config/alibaba/eclipse-codestyle.xml`。不另外维护一份会漂移的 IDEA 配置。

IDEA 导入设置只是编辑便利，不保证与 Eclipse 引擎逐字一致。交付前运行统一 Maven 命令；
也可以在 IDEA 的 Maven 工具窗口运行 `spotless:apply` / `spotless:check`。
避免同时启用另一套保存时自动格式化设置，导致两种风格反复改写。

## 验证配置本身

```bash
python3 -B -m unittest discover -s scripts -p 'test_java_quality.py' -v
```

测试覆盖 Git 选择范围、基线缺失、失败退出，以及真实 Maven 格式检查、修复、幂等性、
修复后与 Checkstyle 的一致性。Java 17 样例包含 record、匿名类和文本块，格式化前后
编译执行并比较输出。测试临时项目只放入 `tmp/`，结束后自动清理。

2026-11-07 首次审计：8 个配置与范围测试通过，quality profile 中的 161 个业务测试通过；
全量 Spotless 检查在 152 个 Java 文件中发现 144 个需要格式化，Checkstyle 扩展规则在
120 个文件中报告 1918 处违规。quality 构建在格式检查阶段以失败退出，未改写业务源码。
随后完成全量迁移：移除通配符及无用导入、补齐分支和循环大括号、拆分变量声明、整理长字符串与排版。
长 SSE 文本块使用续行保持报文原值，另对 151 个已有 Java 文件比较格式整理前后的字符串常量值，结果一致。
强制类型转换后补一个空格，使 formatter 与现有 Checkstyle 空白规则一致。
迁移后验证：`check --all`、`check --base origin/main` 和 `mvn -Pquality verify` 均通过，
168 项 Java 测试、10 项前端测试、8 项配置与范围测试通过；课程与认证审计均为零问题。
上述测试未调用真实外部模型接口。首次审计结果不是豁免配额或将来的通过标准。

依据：[Spotless Maven 文档](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md)、
[Checkstyle 行宽规则](https://checkstyle.sourceforge.io/checks/sizes/linelength.html)、
[IDEA 官方导入说明](https://www.jetbrains.com/help/idea/configuring-code-style.html#import-export-schemes)。
