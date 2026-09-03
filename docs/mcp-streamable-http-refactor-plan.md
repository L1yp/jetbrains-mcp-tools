# MCP Toolbox 独立化与多 Agent 自动配置重构计划

## 1. 文档状态

- 状态：待实施
- 目标版本：下一主功能版本
- 最低 IDE：IntelliJ IDEA 2023.2.7（build `232.10319.17`）
- 最高验证 IDE：IntelliJ IDEA 2025.1.x（build `251.*`）
- JVM 字节码目标：Java 17
- MCP 协议版本：仅 `2025-11-25`
- MCP Transport：Streamable HTTP
- MCP 工具范围：获取可重启 Run Configuration、重启 Run Configuration

本文档取代继续扩展 JetBrains MCP Server Plugin `1.0.30`、旧 `/api/mcp/{toolName}` REST 接口和 Node stdio Proxy 的方案。目标实现不保留尚未正式发布版本的兼容分支，只维护最终架构。

## 2. 已确认的产品决策

### 2.1 必须实现

1. 完全移除 `com.intellij.mcpServer` 运行时和编译依赖。
2. 完全移除 Node Proxy，不要求用户安装 Node.js。
3. 复用 IntelliJ Platform Built-in Web Server。
4. 注册本插件独占的 MCP 路由：`/api/jetbrains-mcp-tools`。
5. 严格实现 MCP `2025-11-25` Streamable HTTP tools-only 子集。
6. 只公开两个工具：
   - `get_restartable_run_configurations`
   - `restart_run_configuration`
7. 插件设置页允许用户多选其使用的 Coding Agent。
8. 对已确认配置契约的 Agent 自动创建或更新项目级配置。
9. 配置文件已经存在时，只合并本插件的 MCP Server 节点，不覆盖整个文件，不删除用户其他配置。
10. 动态端口、鉴权信息和本机路径不得进入 Git。
11. 对配置契约不稳定或无法可靠自动加载的 Agent，只展示 MCP 关键信息和人工配置示例。
12. 后续可以增加用户自定义模板，但不纳入第一版。

### 2.2 明确不做

1. 不兼容 JetBrains 旧 `/api/mcp/list_tools`、`/api/mcp/{toolName}` 接口。
2. 不兼容 MCP `2024-11-05` HTTP+SSE。
3. 不实现 MCP `2026-07-28`。
4. 不实现 Resources、Prompts、Sampling、Elicitation、Tasks、Logging 或 Completion。
5. 不实现长期 SSE 流、事件恢复或 `Last-Event-ID`。
6. 不代理 JetBrains 官方 MCP Server 的其他工具。
7. 不扫描端口。
8. 不在不同 IDEA 进程之间静默选择一个实例。
9. 不自动修改已被 Git 跟踪且包含动态 URL 的共享配置。
10. 不为未确认格式的客户端猜测私有配置路径。

## 3. 最终架构

```text
Coding Agent
    │
    │ MCP 2025-11-25 Streamable HTTP
    ▼
http://127.0.0.1:<IDE_PORT>/api/jetbrains-mcp-tools
    │
    ▼
IntelliJ Built-in Web Server
    │
    ▼
McpStreamableHttpService
    │
    ├── 协议校验与 JSON-RPC 分发
    ├── Bearer Token → Project 绑定
    └── ToolRegistry
            │
            ├── GetRestartableRunConfigurationsTool
            └── RestartRunConfigurationTool
                    │
                    ▼
            IntelliJ RunManager / ExecutionManager

项目启动
    │
    ├── 获取 Built-in Server 实际端口
    ├── 获取或生成项目级 Token
    ├── 获取项目配置写入租约
    └── AgentConfigCoordinator
            │
            ├── CodexConfigAdapter
            ├── TraeConfigAdapter
            ├── QoderConfigAdapter
            ├── OhMyPiConfigAdapter
            ├── KimiCodeConfigAdapter
            ├── ZCodeConfigAdapter
            ├── OpenCodeConfigAdapter
            └── MiMoCodeConfigAdapter
```

## 4. 项目身份与安全边界

### 4.1 不把 `projectPath` 暴露为必填工具参数

每个项目拥有独立 Bearer Token。Agent 项目配置中的请求头为：

```http
Authorization: Bearer <project-token>
```

服务端维护：

```text
project-token → Project
```

因此同一个 IDEA 进程即使打开多个项目，也能在 HTTP 层确定目标项目。工具调用不需要让模型重复传递绝对路径，也不会因为模型选择错误路径而操作另一个项目。

### 4.2 Token 生命周期

1. 使用 `SecureRandom` 生成至少 256 bit 随机值。
2. 使用 URL-safe Base64 编码，不带 padding。
3. 持久化到项目 workspace 级配置，不进入共享项目文件。
4. 正常 IDE 重启后保持不变，避免无意义重写所有 Agent 配置。
5. 用户可在设置页手动轮换；轮换后重新同步所有选中 Agent。
6. 服务端使用常量时间比较。
7. 日志、异常和 UI 状态不显示完整 Token。

### 4.3 HTTP 安全

1. 只接受 loopback 来源的连接；即使用户打开 Built-in Server 的外部连接，也拒绝非 loopback 客户端。
2. 验证 `Origin`：缺失时允许原生客户端；存在时只允许明确的 loopback Origin。
3. 验证 `Host` 为 loopback host 与当前实际端口。
4. 所有 MCP POST 都要求 Bearer Token。
5. 限制请求体大小，初始上限设为 1 MiB。
6. 不记录 Authorization Header 和完整请求体。
7. 对重复失败鉴权做轻量限流，不影响正常本地调用。

## 5. MCP `2025-11-25` 实现范围

### 5.1 Transport 行为

只实现单一 endpoint：

```text
/api/jetbrains-mcp-tools
```

行为：

| 请求 | 行为 |
|---|---|
| `POST` JSON-RPC request | 返回 `application/json` JSON-RPC response |
| `POST` notification | 返回 `202 Accepted`，无响应体 |
| `GET` | 返回 `405 Method Not Allowed` |
| `DELETE` | 返回 `405 Method Not Allowed` |
| 其他 method | 返回 `405 Method Not Allowed` |

服务端不返回 `MCP-Session-Id`，采用无会话实现。MCP `2025-11-25` 的 Session 是可选能力，当前两个工具不需要跨请求状态。

### 5.2 HTTP Header 校验

1. `Content-Type` 必须为 `application/json` 或兼容的 JSON media type。
2. `Accept` 必须包含 `application/json` 和 `text/event-stream`；服务端实际返回 JSON。
3. 初始化后的请求必须携带：

```http
MCP-Protocol-Version: 2025-11-25
```

4. 不支持的版本返回 HTTP `400` 和明确的 JSON-RPC 错误。
5. 不接受 JSON-RPC batch 数组。

### 5.3 JSON-RPC 方法

必须支持：

| 方法 | 类型 | 说明 |
|---|---|---|
| `initialize` | request | 只协商 `2025-11-25` |
| `notifications/initialized` | notification | 接受并返回 `202` |
| `ping` | request | 返回空对象 |
| `tools/list` | request | 返回固定的两个工具 |
| `tools/call` | request | 校验并执行工具 |
| `notifications/cancelled` | notification | 接受；对已完成的短操作无额外行为 |

错误码：

| 场景 | JSON-RPC code |
|---|---:|
| JSON 解析失败 | `-32700` |
| 请求结构错误 | `-32600` |
| 未支持的方法 | `-32601` |
| 参数错误 | `-32602` |
| 非预期服务端错误 | `-32603` |

工具业务错误必须使用 `CallToolResult.isError=true`，而不是协议错误。例如配置不存在、配置未公开、配置名有歧义、存在多个运行实例等。

### 5.4 InitializeResult

```json
{
  "protocolVersion": "2025-11-25",
  "capabilities": {
    "tools": {
      "listChanged": false
    }
  },
  "serverInfo": {
    "name": "jetbrains-mcp-tools",
    "version": "<plugin-version>"
  },
  "instructions": "Call get_restartable_run_configurations before restart_run_configuration. Never guess a configuration name."
}
```

工具集合固定，因此不发送 `notifications/tools/list_changed`。

## 6. 工具契约

### 6.1 `get_restartable_run_configurations`

输入：

```json
{}
```

只返回：

1. 已保存的 Run Configuration。
2. 当前项目设置中允许公开的配置类型。
3. 不返回临时配置。

输出：

```json
{
  "projectName": "order-service",
  "configurations": [
    {
      "name": "OrderApplication",
      "typeId": "SpringBootApplicationConfigurationType",
      "typeName": "Spring Boot"
    }
  ]
}
```

Tool annotations：

```json
{
  "readOnlyHint": true,
  "destructiveHint": false,
  "idempotentHint": true,
  "openWorldHint": false
}
```

### 6.2 `restart_run_configuration`

输入：

```json
{
  "configurationName": "OrderApplication"
}
```

执行规则：

1. `configurationName` 去除首尾空白后不能为空。
2. 只允许当前 Token 绑定项目中的配置。
3. 只允许已保存配置。
4. 必须通过 Run Configuration 类型白名单。
5. 同名配置为多个时拒绝。
6. 多个并行运行实例时拒绝，避免误停错误进程。
7. 未运行时启动；已运行时使用 IDE 原生 restart 流程。
8. 所有需要 EDT 的操作通过明确的调度边界执行。

输出：

```json
{
  "configurationName": "OrderApplication",
  "action": "restart_scheduled",
  "previousRunningInstances": 1,
  "target": "Default",
  "projectName": "order-service"
}
```

Tool annotations：

```json
{
  "readOnlyHint": false,
  "destructiveHint": true,
  "idempotentHint": false,
  "openWorldHint": false
}
```

两个工具均声明 `inputSchema` 与 `outputSchema`，成功时同时返回 `content` 和 `structuredContent`。

## 7. IntelliJ Platform 兼容策略

### 7.1 构建基线

1. `platformVersion` 降到 IntelliJ IDEA Ultimate `2023.2.7`。
2. `sinceBuild` 改为 `232`。
3. Kotlin/JVM 字节码目标改为 Java 17。
4. 在 Java 17 字节码下同时验证 Java 17 和 Java 21 IDE。
5. 保持 IntelliJ Platform Gradle Plugin 2.x，若 232 构建暴露问题再选择兼容版本，不预先降级。

### 7.2 Built-in Web Server

新增 `McpStreamableHttpService`，通过 `com.intellij.httpRequestHandler` 注册。优先直接继承 232 已存在的 `RestService`，但不得依赖 JetBrains MCP 插件类型。

验证重点：

1. 232、242、251 的 `HttpRequestHandler`/`RestService` 二进制兼容。
2. Netty 请求体读取与响应发送 API。
3. keep-alive 行为。
4. HTTP 202、400、401、403、405、413、500 状态。
5. 插件动态卸载后 handler 不再可用。

如果 `RestService` 在目标版本间存在不可接受的不稳定性，退回直接实现 `HttpRequestHandler`，仍复用 Built-in Web Server，不启动独立端口。

## 8. Agent 自动配置框架

### 8.1 统一模型

```kotlin
data class McpEndpoint(
    val serverName: String,
    val url: String,
    val authorizationHeader: String,
    val protocolVersion: String,
    val startupTimeoutMillis: Long,
    val toolTimeoutMillis: Long,
    val enabledTools: List<String>,
)
```

```kotlin
interface AgentConfigAdapter {
    val id: String
    val displayName: String
    val supportLevel: SupportLevel

    fun detect(project: Project): AgentDetection
    fun locate(project: Project): AgentConfigLocation
    fun preview(project: Project, endpoint: McpEndpoint): ConfigChange
    fun apply(change: ConfigChange): ApplyResult
    fun remove(project: Project): ApplyResult
    fun reloadInstruction(): String
}
```

```kotlin
enum class SupportLevel {
    STABLE_AUTO_CONFIG,
    EXPERIMENTAL_AUTO_CONFIG,
    MANUAL_INFORMATION_ONLY,
}
```

### 8.2 配置写入通用规则

这是所有自动适配器的强制规则：

1. 配置不存在时创建父目录和最小配置文件。
2. 配置存在时先完整读取并解析。
3. 只添加或更新本插件拥有的 MCP Server 节点。
4. 保留其他 MCP Server、模型、权限、Agent、插件、注释和未知字段。
5. 解析失败时禁止写入，展示文件、错误位置和人工配置内容。
6. 不允许“解析失败后用新文件覆盖”。
7. 配置内容不变时不得触碰文件时间。
8. 写入采用同目录临时文件、flush/fsync 和原子替换。
9. 写入前后计算内容摘要，检测并发修改；检测到竞争时重新读取并重算一次，仍冲突则失败。
10. 本插件删除配置时只删除自己拥有的 MCP 节点；其他内容为空且文件由插件创建时才允许删除文件。
11. UTF-8、UTF-8 BOM、LF、CRLF 和原文件末尾换行风格需要保留。
12. 日志不得输出 Token。

### 8.3 所有权识别

使用项目 workspace 状态记录每个适配器：

```text
adapterId
configPath
serverName
lastAppliedEndpointHash
createdFileByPlugin
```

更新前判断：

1. Server 名称与记录一致。
2. URL path 为 `/api/jetbrains-mcp-tools`。
3. 如果用户修改了本插件节点且摘要不一致，先展示差异并要求用户在设置页确认，不静默覆盖。
4. 如果目标名称已被其他配置占用，生成带短 ID 的名称，例如 `jetbrains_tools_a81f2c`。

### 8.4 Git 安全

1. 默认把插件生成的精确配置路径加入当前 clone 的 `.git/info/exclude`。
2. 不自动修改仓库共享 `.gitignore`。
3. 写入前使用 IntelliJ VCS API 或 Git 只读查询判断文件是否已被跟踪。
4. `.gitignore` 和 `.git/info/exclude` 对已跟踪文件无效；遇到已跟踪文件时不自动写动态 URL。
5. 已跟踪文件优先寻找该 Agent 官方支持的 local override 文件。
6. 没有 local override 时降级为人工配置信息，提示用户自行决定是否解除跟踪。
7. 插件不得自动执行 `git rm --cached`、`git update-index --skip-worktree` 或 `assume-unchanged`。

### 8.5 JSON、JSONC、TOML

1. 新文件优先生成严格 JSON，除非 Agent 只支持其他格式。
2. 已有 JSONC 必须通过支持注释和尾随逗号的语法树更新，不能先转成普通 JSON 再覆盖。
3. Codex TOML 使用语法感知更新或受控 managed block；不得重复声明同一 table。
4. JSON 对象按 Agent 实际 schema 更新，不能假定都使用顶层 `mcpServers`。

## 9. 第一批稳定自动配置适配器

### 9.1 Codex

- 路径：`.codex/config.toml`
- 节点：`[mcp_servers.<name>]`
- Transport：`url`
- Header：`http_headers`
- 工具限制：`enabled_tools`
- 重载：新建任务或重启 Codex
- 附加要求：只在可信项目中加载

已有文件必须保留所有非本插件 table。若 `.codex/config.toml` 已被 Git 跟踪，则停止自动写入并降级为人工配置。

### 9.2 Trae / TraeCode

- 路径：`.trae/mcp.json`
- 节点：`mcpServers.<name>`
- Transport：`type: "http"`
- Header：`headers`
- 重载：按客户端版本提示重新加载 MCP 或新建会话

### 9.3 Qoder

- 优先路径：`.qoder/settings.local.json`
- 节点：`mcpServers.<name>`
- Transport：`type: "http"`
- Header：`headers`
- 工具限制：`includeTools`
- 重载：`/mcp reload` 或新会话

优先使用官方 local 配置，避免修改共享 `.qoder/settings.json`。

### 9.4 Oh My Pi

- 路径：`.omp/mcp.json`
- 节点：`mcpServers.<name>`
- Transport：`type: "http"`
- Header：`headers`
- 重载：`/mcp reload`

### 9.5 Kimi Code

- 路径：`.kimi-code/mcp.json`
- 节点：`mcpServers.<name>`
- Transport：存在 `url` 且不声明 `transport`；SSE 才显式声明
- Header：`headers`
- 重载：新增 Server 只在新会话注册

### 9.6 ZCode

- 优先路径：`.zcode/config.json`
- 节点：`mcp.servers.<name>`
- Transport：`type: "http"`
- Header：`headers`
- 兼容路径：`.agents/mcp.json`，第一版不默认写入，避免与其他 Agent 共享文件冲突

### 9.7 OpenCode

- 优先路径：`.opencode/opencode.json` 或已存在的 `.jsonc`
- 节点：`mcp.servers.<name>`
- Transport：`type: "remote"`
- Header：`headers`
- OAuth：`oauth: false`
- 工具暴露：`codemode: false`
- 开关：`disabled: false`，不得写不存在的 `enabled`
- Timeout：`startup`、`catalog`、`execution`

如果项目根已经存在 `opencode.json(c)`，仍优先使用已存在的最高优先级配置；不得制造用户难以理解的重复定义。

### 9.8 MiMoCode

- 路径：`.mimocode/mimocode.json` 或已存在的 `.jsonc`
- 节点：`mcp.<name>`，注意没有 `servers` 层
- Transport：`type: "remote"`
- Header：`headers`
- OAuth：`oauth: false`
- 开关：`enabled: true`
- Timeout：单一毫秒数

需要按 MiMoCode schema 单独序列化，不能复用 OpenCode V2 的 `mcp.servers` 结构。检测到 `mimo serve`/`attach` 相关版本时，在 UI 展示 MCP 初始化能力警告，但不阻止普通 TUI 配置。

## 10. 第一版仅展示人工配置信息的客户端

设置页为这些客户端展示统一信息：

```text
Protocol: MCP 2025-11-25
Transport: Streamable HTTP
URL: http://127.0.0.1:<port>/api/jetbrains-mcp-tools
Header: Authorization: Bearer <token>
Tools:
  - get_restartable_run_configurations
  - restart_run_configuration
```

同时提供：

- 复制 URL
- 复制 Header JSON
- 复制通用 `mcpServers` JSON
- 复制 `tools/list` 自检命令
- 打开官方配置文档

### 10.1 MiniMax Code

原因：配置路径和运行时行为仍在变化，现有版本出现第三方 MCP 已配置但工具未注入 Agent 的问题。第一版只展示关键信息，不自动修改 `~/.mavis/mcp/mcp.json`、`~/.minimax/...` 或其他候选路径。

### 10.2 DeepSeek Harness

原因：当前 MCP Client 通过 Cordis composition/profile patch 加载，缺少稳定的 workspace-scoped 自动配置契约。第一版展示可复制的 `@deepseek-ai/dsh-mcp-client` Streamable HTTP Cordis row，不自动合并用户 YAML。

### 10.3 WorkBuddy

原因：公开契约主要面向 Connector 包，而不是普通项目目录自动发现。第一版展示 Connector `mcp.json` 内容并允许导出文本，不自动安装 Connector。

### 10.4 Pi 原版

原因：未确认稳定的原生 MCP 配置入口。第一版只显示“当前版本未验证自动配置”，不写任何文件。

### 10.5 后续模板扩展

后续版本可以允许用户为 `MANUAL_INFORMATION_ONLY` 客户端配置模板：

```text
目标路径
配置格式：JSON / JSONC / TOML / YAML
Server 节点路径
URL 字段名
Transport 字段和值
Header 字段名
Reload 提示
```

第一版不实现，避免在核心协议和稳定适配器尚未完成时引入模板 DSL、路径变量、安全校验和错误恢复复杂度。

## 11. Agent 设置页

### 11.1 交互模型

使用多选表格而不是单选框：

| Agent | 检测 | 支持级别 | 配置文件 | 状态 |
|---|---|---|---|---|
| Codex | 已检测 | 自动配置 | `.codex/config.toml` | 已同步 |
| Trae | 未检测 | 自动配置 | `.trae/mcp.json` | 未选择 |
| Qoder | 已检测 | 自动配置 | `.qoder/settings.local.json` | 待同步 |
| Oh My Pi | 已检测 | 自动配置 | `.omp/mcp.json` | 已同步 |
| Kimi Code | 已检测 | 自动配置 | `.kimi-code/mcp.json` | 需新会话 |
| ZCode | 已检测 | 自动配置 | `.zcode/config.json` | 已同步 |
| OpenCode | 已检测 | 自动配置 | `.opencode/opencode.jsonc` | 已同步 |
| MiMoCode | 已检测 | 自动配置 | `.mimocode/mimocode.jsonc` | 已同步/有警告 |
| MiniMax Code | 已检测 | 人工配置 | — | 复制配置 |
| DeepSeek Harness | 已检测 | 人工配置 | — | 复制 Cordis row |
| WorkBuddy | 未检测 | 人工配置 | — | 复制 Connector 配置 |
| Pi | 未验证 | 人工配置 | — | 查看关键信息 |

### 11.2 操作

- 自动检测 Agent
- 选择/取消选择
- 预览变更
- 同步选中 Agent
- 移除本插件配置
- 打开配置文件
- 复制人工配置信息
- 测试 MCP Endpoint
- 轮换 Token

### 11.3 状态

```text
未检测
未选择
配置不存在
待同步
已同步
配置被 Git 跟踪
配置解析失败
配置被用户修改
被另一个 IDEA 实例占用
需要新会话
客户端版本存在已知问题
人工配置
```

## 12. 多 IDEA 进程与项目配置租约

### 12.1 问题

同一个物理项目目录被两个 IDEA 进程打开时，两个进程可能使用不同 Built-in Server 端口。如果都写 Agent 配置，会不断覆盖 URL。

### 12.2 方案

在用户级目录创建项目租约：

```text
~/.jetbrains-mcp-tools/locks/<canonical-project-path-sha256>.lock
```

1. 使用 `FileChannel.tryLock()` 持有整个项目生命周期。
2. 获得租约的 IDEA 负责生成 Agent 配置。
3. 未获得租约的 IDEA 仍可以运行 MCP Handler，但不得覆盖项目 Agent 配置。
4. 未获得租约时在设置页显示拥有者 PID、IDE build 和 endpoint。
5. 拥有者退出或崩溃后操作系统释放文件锁。
6. 等待者周期性低频重试，接管后才重新同步配置。
7. 不使用“最后写入者获胜”。

不同 Git worktree 有不同 canonical path，因此各自独立。

## 13. 自动同步生命周期

### 13.1 项目启动

1. 等待项目打开完成。
2. 获取 Built-in Web Server 实际端口。
3. 确认 MCP Handler 已注册。
4. 获取项目 Token。
5. 获取项目配置租约。
6. 使用本地 `initialize → tools/list` 做 endpoint 自检。
7. 自检成功后同步用户选中的 Agent。
8. 如果配置变化，提示相应 Agent 的重载方式。

### 13.2 运行中

只在以下事件重新同步：

- 用户保存 Agent 选择。
- Built-in Server 端口变化。
- Token 轮换。
- 项目租约接管。
- 用户点击“同步”。

不得通过心跳频繁改写 Agent 配置。

### 13.3 项目关闭

1. 释放项目租约。
2. 从服务端 Token Registry 移除 Project。
3. 不立即删除 Agent 配置；下次 IDEA 启动会更新 URL。
4. 当前 Agent 调用返回连接失败是正常的“IDE 未运行”状态。

## 14. 现有代码迁移清单

### 14.1 删除

- `build.gradle.kts` 中的 `plugin("com.intellij.mcpServer:...")`
- `plugin.xml` 中的 `<depends>com.intellij.mcpServer</depends>`
- `mcpServer.mcpTool` 扩展注册
- `org.jetbrains.ide.mcp.*` 类型引用
- `org.jetbrains.mcpserverplugin.*` 类型引用
- `McpToolManager` 使用
- `FilteredProxyManager`
- `mcp-service-restart-proxy.mjs`
- Node Proxy 集成测试和 Gradle task
- 当前实例注册文件中为 Proxy 服务的字段和逻辑
- `get_ide_identity` 工具
- STDIO Proxy 配置页
- 旧 HTTP REST 说明页

### 14.2 保留并重构

- `RunStateTracker`
- Run Configuration 类型白名单
- `RunConfigurationExposureCatalog`
- `RestartServiceTool` 的 IDE 执行逻辑
- `GetRestartableRunConfigurationsTool` 的查询逻辑
- 设置页和 onboarding 框架
- 多项目和同名配置安全校验

### 14.3 新增

```text
mcp/
  McpStreamableHttpService.kt
  McpJsonRpcDispatcher.kt
  McpProtocolModels.kt
  McpHttpSecurity.kt
  ToolRegistry.kt
  McpProjectTokenService.kt

agentconfig/
  AgentConfigAdapter.kt
  AgentConfigCoordinator.kt
  AgentDetectionService.kt
  ProjectConfigLease.kt
  GitExcludeManager.kt
  JsonConfigEditor.kt
  JsoncConfigEditor.kt
  TomlConfigEditor.kt
  adapters/
    CodexConfigAdapter.kt
    TraeConfigAdapter.kt
    QoderConfigAdapter.kt
    OhMyPiConfigAdapter.kt
    KimiCodeConfigAdapter.kt
    ZCodeConfigAdapter.kt
    OpenCodeConfigAdapter.kt
    MiMoCodeConfigAdapter.kt
    ManualAgentCatalog.kt
```

## 15. 实施阶段

### 阶段 0：232 技术验证

目标：在 IDEA 2023.2.7 上证明最小链路可用。

- Java 17 编译
- 注册 `/api/jetbrains-mcp-tools`
- `POST initialize`
- `GET` 返回 405
- loopback 校验
- Codex 或 MCP Inspector 完成握手

验收：真实 IDEA 2023.2.7 上获得合法 `InitializeResult`。

### 阶段 1：解除旧依赖

- 移除 JetBrains MCP Server Plugin
- 移除 Node Proxy
- 建立自有工具接口
- 迁移两个工具
- 删除旧实现和测试

验收：插件在未安装官方 MCP Server 的 IDEA 2023.2.7 启动。

### 阶段 2：MCP tools-only Server

- 完成 HTTP、JSON-RPC、版本和错误处理
- 完成 tools/list、tools/call
- 完成 input/output schema 与 annotations
- 完成 structuredContent

验收：协议测试与两个工具调用通过。

### 阶段 3：项目 Token 与安全

- Project Token 服务
- loopback、Origin、Host、Authorization 校验
- 请求大小限制
- 日志脱敏
- 多项目 Token 路由

验收：错误项目、错误 Token、远端来源无法调用重启。

### 阶段 4：配置编辑基础设施

- 项目配置租约
- `.git/info/exclude`
- Git 跟踪检测
- 原子写入与并发摘要检查
- JSON、JSONC、TOML 结构化合并
- 所有权状态与回滚

验收：已有复杂配置不丢字段、注释或其他 MCP Server。

### 阶段 5：稳定 Agent 适配器

按风险从低到高：

1. Kimi Code
2. Trae
3. Oh My Pi
4. Qoder
5. ZCode
6. OpenCode
7. MiMoCode
8. Codex TOML

每个适配器独立提交并带 golden fixture 测试，避免一个总提交混合所有客户端。

### 阶段 6：人工配置客户端

- MiniMax Code
- DeepSeek Harness
- WorkBuddy
- Pi

验收：设置页能复制准确的 endpoint、headers、工具清单和官方文档链接，不写不确定文件。

### 阶段 7：UI、文档与迁移

- Agent 多选设置页
- 配置预览、状态和错误反馈
- 首次安装引导
- 从当前版本升级时删除旧 Proxy 产物提示
- README、故障排查和安全说明

### 阶段 8：发布验证

- 全量自动化测试
- 三个 IDEA 版本 Plugin Verifier
- 三个真实 IDE 沙箱
- 8 个稳定 Agent 的实际连接验证
- 安装、升级、禁用、卸载、动态卸载验证

## 16. 测试计划

### 16.1 协议测试

- initialize 成功与版本拒绝
- string/number JSON-RPC id
- malformed JSON
- batch 拒绝
- notification 返回 202
- GET/DELETE 返回 405
- Content-Type/Accept 校验
- MCP-Protocol-Version 校验
- method not found
- invalid params
- 业务错误使用 `isError`
- `content` 与 `structuredContent` 一致
- input/output schema 合法

### 16.2 安全测试

- 缺失 Token
- 错误 Token
- Token 常量时间比较路径
- 非 loopback remote address
- 非法 Origin
- 非法 Host
- 超大请求返回 413
- 日志不包含 Token
- 一个项目 Token 不能调用另一个项目

### 16.3 工具测试

- 空列表
- 只返回持久配置
- 类型白名单
- 配置名不存在
- 配置名空白
- 同名歧义
- 未运行时启动
- 单实例运行时重启
- 多实例运行时拒绝
- 不支持当前 ExecutionTarget
- IDE runner 不存在
- EDT 与后台线程调用

### 16.4 配置合并测试

每个适配器至少覆盖：

- 文件不存在
- 文件存在且为空
- 已有其他 MCP Server
- 已有模型和权限配置
- 已有本插件节点
- 用户修改过本插件节点
- 配置语法错误
- UTF-8 BOM
- 中文内容
- LF/CRLF
- 文件末尾无换行
- 并发修改
- 原子替换失败
- 文件只读
- 已被 Git 跟踪
- `.git/info/exclude` 已存在同项
- JSONC 注释和尾随逗号
- TOML 已存在相同 table

### 16.5 多进程测试

- 一个 IDEA、一个项目
- 一个 IDEA、多个项目
- 多个 IDEA、不同项目
- 多个 IDEA、同一项目
- 租约拥有者正常退出
- 租约拥有者崩溃
- 等待者接管并更新 URL
- 同一项目不同 worktree

### 16.6 兼容矩阵

| IDE | Build | JVM | 要求 |
|---|---|---:|---|
| IDEA 2023.2.7 | 232.10319.17 | 17 | 编译基线、真实运行、Verifier |
| IDEA 2024.2.5 | 242.24807.4 | 21 | 真实运行、Verifier |
| IDEA 2025.1.7.1 | 251.29188.36 | 21 | 真实运行、Verifier |

Agent 验证时记录具体版本和日期，不使用“最新版”作为不可复现的测试记录。

## 17. 验收标准

### 17.1 核心 Server

- 未安装 `com.intellij.mcpServer` 时插件可安装、启用和工作。
- IDEA 2023.2.7 可加载 Java 17 插件。
- MCP Client 通过 Streamable HTTP 完成初始化和两个工具调用。
- 端口变化后选中 Agent 的本地项目配置正确更新。
- 不存在 Node.js 运行时依赖。

### 17.2 配置安全

- 已有配置绝不被整文件覆盖。
- 其他 MCP Server 和非 MCP 设置不丢失。
- 语法错误配置不被修改。
- 动态端口和 Token 不进入 Git。
- 已跟踪配置不会被静默修改。
- 同项目双 IDEA 不发生 URL 写入竞争。

### 17.3 Agent 支持

- 8 个稳定适配器均通过实际客户端验证。
- 不稳定客户端只展示人工信息，不写私有路径。
- 每个客户端都有准确的重载提示。
- 配置移除只删除本插件节点。

## 18. 工作量估算

| 工作包 | 估算 |
|---|---:|
| 232/Java 17 技术验证 | 1–2 天 |
| 移除旧依赖与迁移两个工具 | 1–2 天 |
| MCP 2025-11-25 HTTP/JSON-RPC | 2–3 天 |
| 项目 Token、安全与多项目路由 | 1–2 天 |
| 配置租约、Git、原子合并基础设施 | 2–3 天 |
| 5 个常规 JSON 稳定适配器 | 2–4 天 |
| OpenCode/MiMoCode JSONC | 1–2 天 |
| Codex TOML | 1–2 天 |
| 人工配置目录与 UI | 1–2 天 |
| 真实客户端与三版 IDEA 验证 | 2–3 天 |

预计总量：`13–23` 个开发日。若已有配置的 JSONC/TOML 保真编辑和客户端版本差异较少，可接近下限；如果需要修复各客户端实际加载差异，则接近上限。

## 19. 主要风险

| 风险 | 处理 |
|---|---|
| 232–251 Built-in Server API 不稳定 | 阶段 0 先验证；Plugin Verifier 覆盖三版 |
| 手写协议遗漏 | 固定 `2025-11-25`；协议 golden tests；MCP Inspector 实测 |
| 配置合并损坏用户文件 | 语法树更新、摘要竞争检测、原子替换、禁止解析失败覆盖 |
| JSONC/TOML 格式丢失 | 专用编辑器，不经普通 JSON round-trip |
| 动态端口导致 Agent 配置过期 | 项目启动自检后同步；明确新会话提示 |
| 多 IDEA 争写 | 项目级跨进程租约 |
| Token 泄漏到 Git/日志 | `.git/info/exclude`、跟踪检测、日志脱敏 |
| 客户端格式快速变化 | adapter 独立版本检测和 fixture；不确定时降级人工信息 |
| MiniMax/DeepSeek 等尚未稳定 | 第一版不自动写文件 |

## 20. 提交拆分建议

实施时按独立用户价值拆分提交：

1. `build: 将插件兼容基线降至 IDEA 2023.2.7 和 Java 17`
2. `refactor: 移除旧 MCP Server 与 Node Proxy 依赖`
3. `feat: 实现 MCP 2025-11-25 Streamable HTTP 服务`
4. `feat: 提供 Run Configuration 查询与重启工具`
5. `feat: 增加项目 Token、HTTP 安全与多项目路由`
6. `feat: 增加 Agent 配置合并和项目租约基础设施`
7. 每个稳定 Agent 适配器一个独立提交或按同格式小组提交
8. `feat: 增加不稳定客户端人工配置信息`
9. `docs: 更新安装、Agent 配置和故障排查文档`

## 21. 参考资料

- [IntelliJ IDEA 2023.2.7 RestService](https://github.com/JetBrains/intellij-community/blob/idea/232.10319.17/platform/built-in-server/src/org/jetbrains/ide/RestService.kt)
- [MCP 2025-11-25 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP 2025-11-25 Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [Codex MCP](https://developers.openai.com/codex/mcp)
- [TraeCode MCP](https://docs.trae.cn/cli_model-context-protocol)
- [Qoder MCP Reference](https://docs.qoder.com/cli/mcp-reference)
- [Oh My Pi MCP](https://github.com/can1357/oh-my-pi/blob/main/docs/mcp-config.md)
- [Kimi Code MCP](https://moonshotai.github.io/kimi-code/en/customization/mcp.html)
- [ZCode MCP](https://zcode.z.ai/cn/docs/mcp-services)
- [OpenCode MCP](https://opencode.ai/v2/docs/mcp-servers)
- [MiMoCode](https://github.com/XiaomiMiMo/MiMo-Code)
- [DeepSeek Harness MCP](https://github.com/deepseek-ai/deepseek-harness/blob/master/examples/mcp-memory/README.md)
- [WorkBuddy Connector](https://open.workbuddy.cn/en/docs/connector)
