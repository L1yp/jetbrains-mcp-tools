# MCP Toolbox

![MCP Toolbox 图标](src/main/resources/META-INF/pluginIcon.svg)

MCP Toolbox 是一个面向 JetBrains IDE 自动化的可扩展 MCP 工具插件，通过 IDE Built-in Web Server 提供安全、项目感知的 MCP endpoint。服务端支持 MCP `2025-11-25`、`2025-06-18` 和 `2025-03-26` Streamable HTTP，并为 MCP `2024-11-05` 提供旧式 HTTP+SSE 兼容传输。工具可查询、启动或重启项目 Run Configuration，也可通过 IDEA 已保存的凭据执行 Git fetch、pull 和 push。

计划发布仓库：[`L1yp/jetbrains-mcp-tools`](https://github.com/L1yp/jetbrains-mcp-tools)

插件不依赖 JetBrains 官方 MCP Server Plugin，不需要 Node.js，也不启动独立端口。支持 IntelliJ IDEA Ultimate `2023.2.7`（build `232`）至 `2025.1.x`（build `251`），JVM 字节码目标为 Java 17。

## MCP endpoint

每个 IDEA 进程在 Built-in Web Server 的实际端口注册：

```text
http://127.0.0.1:<IDE_PORT>/api/jetbrains-mcp-tools
```

主 endpoint 使用无会话 Streamable HTTP：

| 请求 | 行为 |
|---|---|
| `POST` JSON-RPC request | 返回 `application/json` JSON-RPC response |
| `POST` notification | 返回 `202 Accepted`，无响应体 |
| 携带 `MCP-Protocol-Version` 的 `GET` | 返回 `405 Method Not Allowed`；服务端不提供 Streamable HTTP 独立消息流 |
| 不携带 `MCP-Protocol-Version` 的 `GET` | 作为旧客户端探测的 HTTP+SSE fallback 建立兼容连接 |
| `DELETE` 或其他 method | 返回 `405 Method Not Allowed` |

Streamable HTTP 客户端请求必须满足：

- `Content-Type` 为 `application/json` 或兼容的 `application/*+json`。
- `Accept` 同时包含 `application/json` 和 `text/event-stream`。
- MCP `2025-06-18` 及之后的客户端在 `initialize` 后携带协商出的 `MCP-Protocol-Version`；未携带该 header 时按规范兼容为 `2025-03-26`。
- 携带设置页为当前项目生成的 `Authorization: Bearer <project-token>`。
- 请求来自 loopback，且 `Origin`（若存在）和 `Host` 均指向 loopback endpoint。

MCP `2024-11-05` 客户端可以把以下地址配置为旧式 SSE endpoint：

```text
http://127.0.0.1:<IDE_PORT>/api/jetbrains-mcp-tools/sse
```

服务端建立长连接后先发送 `endpoint` 事件，事件中的一次性消息地址形如：

```text
http://127.0.0.1:<IDE_PORT>/api/jetbrains-mcp-tools/message?sessionId=<session-id>
```

旧客户端向该地址 POST JSON-RPC 消息，服务端返回 `202 Accepted`，并通过原 SSE 连接的 `message` 事件发送 JSON-RPC response。SSE GET 与消息 POST 都必须携带同一个项目 Bearer Token；`sessionId` 只关联连接和项目，不替代认证。连接每 15 秒发送注释 heartbeat，并在底层 channel 关闭后立即清理。为兼容会先 POST 探测、失败后对同一 URL 发 GET 的客户端，主 endpoint 的无版本 GET 也提供相同 fallback。

服务端实现 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call` 和 `notifications/cancelled`，不支持 batch、Resources、Prompts、Sampling、Tasks、Streamable HTTP 独立 SSE 流或事件重放。`tools/list` 接受标准 `cursor`、`_meta` 和未来扩展参数，但当前工具目录始终一次性完整返回，不产生 `nextCursor`。版本相关响应会按协商结果裁剪：`2024-11-05` 不返回 tool `annotations`，`2025-03-26` 及更早版本不返回 `outputSchema` / `structuredContent`。

## 工具

插件公开 Run Configuration 与 Git 工具，Agent 不需要也不能传入 `projectPath`。Bearer Token 在 HTTP 层绑定唯一项目。

### `get_restartable_run_configurations`

输入为 `{}`。只返回已保存、非临时且类型在项目白名单内的 Run Configuration：

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

### `restart_run_configuration`

必须使用上一个工具返回的精确名称：

```json
{
  "configurationName": "OrderApplication"
}
```

未运行时会启动，单实例运行时使用 IDE 原生 restart 流程。以下情况以 `CallToolResult.isError=true` 返回业务错误，不会猜测或降级执行：

- 名称为空、不存在或同名配置不唯一。
- 配置未公开、属于临时配置或没有可用 Runner。
- 当前 Execution Target 不支持该配置。
- 同一配置存在多个并行运行实例。

MCP `2025-06-18` 及之后的成功结果同时包含文本 `content` 和 `structuredContent`；较早版本只返回兼容的文本 `content`。

### `get_git_repositories`

输入为 `{}`。返回 IDEA 当前管理的 Git 仓库根路径、状态、HEAD、当前分支、上游分支和 remote 名称。后续 Git 工具的 `repositoryRoot` 必须使用这里返回的精确值；单仓库项目可以省略，多仓库项目必须显式指定。

### `get_git_remotes`

返回一个 IDEA 管理仓库的 remote 名称及 fetch/push URL，效果类似只读的 `git remote -v`。未单独配置 push URL 时返回有效的 fetch URL；URL user-info 以及 `access_token`、`private_token`、`oauth_token`、`token`、`password` 查询参数会被替换为 `***`。

```json
{
  "repositoryRoot": "E:/workspace/order-service"
}
```

### `git_fetch`

从已配置的 remote 获取引用。`remote` 省略时依次选择当前分支的上游 remote、`origin` 或唯一 remote；`prune` 默认 `false`。工具不接收任意 URL 或凭据。

```json
{
  "repositoryRoot": "E:/workspace/order-service",
  "remote": "origin",
  "prune": true
}
```

### `git_pull`

从当前分支已配置的 upstream 拉取。默认 `strategy=ff_only`，也可以明确选择 `rebase` 或 `merge`；没有 upstream、处于 detached HEAD 或 Git 产生冲突时返回业务错误。

```json
{
  "repositoryRoot": "E:/workspace/order-service",
  "strategy": "ff_only"
}
```

### `git_push`

把当前本地分支推送到已配置的 remote，不支持 force、不跳过 hooks，也不接受任意 refspec。新分支可用 `setUpstream=true` 建立跟踪关系。

```json
{
  "repositoryRoot": "E:/workspace/order-service",
  "remote": "origin",
  "setUpstream": true
}
```

三个远程工具都通过 Git4Idea 的命令执行链运行。IDEA 会使用其 Git Password Safe、HTTP askpass 和原生 SSH askpass 处理认证，包括已保存的加密私钥口令；MCP 输入、输出和日志不会包含凭据。命令输出中的 URL user-info 也会脱敏。

## 设置与 Agent 配置

打开 `Settings | Tools | MCP Toolbox`：

1. 在 `Coding Agent` 页勾选当前项目实际使用的 Agent。
2. 点击“预览变更”检查脱敏 diff。
3. 点击“同步选中 Agent”，插件会先执行本地 `initialize → tools/list` 自检，再合并配置。
4. 在 `MCP 工具` 页查看完整工具列表，按项目启用或禁用工具。禁用项不会出现在 `tools/list` 中，也不能通过 `tools/call` 调用。
5. 在“可重启的 Run Configuration”页选择允许公开的类型。首次使用默认隐藏 Maven 和 Gradle，临时配置始终不公开。

`Coding Agent` 页的按钮区会持续显示“处理中”“成功”“失败”或提示状态；后台操作执行时会暂时禁用按钮，避免重复提交。设置页还支持自动检测、移除本插件节点、打开配置文件、复制 URL/Header/`tools/list` 命令、测试 endpoint 和轮换项目 Token。普通状态与预览不显示完整 Token；明确点击复制配置或 Header 时，剪贴板内容会包含 Token，请勿提交 Git 或粘贴到日志。

IDE 底部的 `MCP Toolbox` 工具窗口集中显示插件日志，包括 Settings 操作、Endpoint 自检、Agent 同步和 MCP 请求的结果与耗时。窗口最多保留当前项目本次 IDE 会话最近 2000 条日志，支持清空和复制；请求参数不会写入日志，Token、Authorization、URL user-info 和 token query 参数会在记录前脱敏。

为便于区分请求来源，自动生成的 Agent 配置会携带仅用于诊断的 `X-MCP-Client` header；初始化日志还会显示客户端自报的 `clientInfo.name/version` 和可用的 `User-Agent`。这些字段只用于日志标记，可以缺失或被伪造，不参与认证与权限判断。

### 自动配置

| Agent | 项目配置 | 插件节点 | 重载提示 |
|---|---|---|---|
| Codex | `.codex/config.toml` | `mcp_servers.<name>` | 新建任务或重启 Codex |
| Trae / TraeCode | `.trae/mcp.json` | `mcpServers.<name>` | 重载 MCP 或新会话 |
| Qoder | `.qoder/settings.local.json` | `mcpServers.<name>` | `/mcp reload` 或新会话 |
| Oh My Pi | `.omp/mcp.json` | `mcpServers.<name>` | `/mcp reload` |
| Kimi Code | `.kimi-code/mcp.json` | `mcpServers.<name>` | 新会话 |
| ZCode | `.zcode/config.json` | `mcp.servers.<name>` | 重载 MCP 或新会话 |
| OpenCode | 已存在的 `opencode.json(c)`，否则 `.opencode/opencode.json` | `mcp.servers.<name>` | 重载 MCP 或新会话 |
| MiMoCode | 已存在的 `.mimocode/mimocode.jsonc`，否则 `.mimocode/mimocode.json` | `mcp.<name>` | 重载 MCP 或新会话 |

MiMoCode 的 `mimo serve` / `attach` MCP 初始化能力随版本变化，设置页会保留警告；普通 TUI 配置不受此提示阻止。

### 仅人工配置

MiniMax Code、DeepSeek Harness、WorkBuddy 和 Pi 不会被自动写入。设置页只提供 endpoint 关键信息和可复制示例：

- DeepSeek Harness：`@deepseek-ai/dsh-mcp-client` 的 `streamable-http` Cordis row。
- WorkBuddy：Connector `mcp.json`，Token 使用 `${MCP_SERVICE_RESTART_TOKEN}` 环境变量引用。
- MiniMax Code、Pi：通用 `mcpServers` JSON 和自检命令。

## 配置安全

自动配置遵循以下规则：

- 配置不存在时创建最小文件；存在时只添加或更新本插件拥有的 Server 节点。
- 保留其他 MCP Server、模型、权限、未知字段，以及 JSONC/TOML 中的注释。
- 解析失败时拒绝写入，不会用新文件覆盖损坏或未知格式的配置。
- 保留 UTF-8 BOM、LF/CRLF 和文件末尾换行风格；内容不变时不触碰修改时间。
- 使用同目录临时文件、flush/fsync 和原子替换，并以摘要检测并发修改；重算一次后仍竞争就失败。
- 插件记录每个适配器的路径、Server 名称、节点摘要和文件所有权。用户修改过插件节点时先阻止同步，必须在设置页明确确认覆盖。
- 移除配置只删除插件记录的节点；只有文件由插件创建且其余内容为空时才删除文件。

### Git 保护

动态端口和 Token 不应进入版本库：

- 写入前使用 `git ls-files` 判断精确配置路径是否已被跟踪。
- 已跟踪文件一律停止自动写入，`.gitignore` 或 exclude 不能绕过该限制。
- 未跟踪文件会加入当前 clone 的 `.git/info/exclude`，不会修改共享 `.gitignore`。
- 插件不会执行 `git rm --cached`、`skip-worktree` 或 `assume-unchanged`。

## 多 IDEA 进程

项目级 Token 在 workspace 配置中持久化，并在内存中以常量时间比较映射到 `Project`。同一物理项目被多个 IDEA 进程打开时，用户目录下的项目租约保证只有一个进程写 Agent 配置：

```text
~/.jetbrains-mcp-tools/locks/<canonical-project-path-sha256>.lock
```

未获得租约的进程仍可运行自己的 MCP handler，但设置页会显示占用者信息且不会覆盖 Agent URL。租约持有者退出后操作系统释放锁，等待进程低频重试并在接管后重新自检、同步。不同 Git worktree 使用不同 canonical path，彼此独立。

## 故障排查

- `401`：项目 Token 缺失、已轮换或配置属于另一个项目。重新同步 Agent 配置。
- `403`：请求不是 loopback，或 `Origin` / `Host` 与当前 endpoint 不匹配。
- `400`：检查 `Content-Type`、`Accept`、`MCP-Protocol-Version` 和 JSON-RPC 格式。
- `413`：请求体超过 1 MiB。
- 旧式 SSE 消息 endpoint 返回 `404`：对应长连接已关闭或 `sessionId` 已失效，重新连接 SSE endpoint。
- “配置被 Git 跟踪”：把动态配置迁移到该 Agent 的 local override；插件不会自动解除跟踪。
- “配置被用户修改”：先预览差异，只在确认本插件节点应被替换时点击“覆盖当前节点”。
- “被另一个 IDEA 实例占用”：关闭占用该项目的另一个 IDEA，等待租约接管后再同步。
- Agent 仍看不到工具：按表格中的重载方式新建会话或重载 MCP，并在设置页执行 endpoint 自检。

从旧版本升级后，Node Proxy 和 `instances/` 注册表不再使用。确认没有旧版插件实例后，可手动删除 `~/.mcp-service-restart/mcp-service-restart-proxy.mjs` 和 `~/.mcp-service-restart/instances/`；保留 `locks/`。

## 开发与验证

构建只需要 JDK 17，不需要 Node.js。编译基线是 IntelliJ IDEA Ultimate `2023.2.7`。

IntelliJ Platform Gradle Plugin 2.x 的 searchable-options 生成任务最低要求 build 233，因此构建在 232 基线上显式关闭该任务；设置页仍可从 `Settings | Tools | MCP Toolbox` 打开，但不会进入 Settings 全文搜索索引。

```powershell
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
```

Plugin Verifier 固定覆盖：

- IntelliJ IDEA Ultimate `2023.2.7` / JVM 17
- IntelliJ IDEA Ultimate `2024.2.5` / JVM 21
- IntelliJ IDEA Ultimate `2025.1.7.1` / JVM 21

插件 ZIP 生成在 `build/distributions/`。

## 参考

- [MCP 2025-11-25 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP 2025-11-25 Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [MCP 2025-06-18 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [MCP 2024-11-05 HTTP+SSE](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)
- [Codex MCP](https://developers.openai.com/codex/mcp)
- [Kimi Code MCP](https://moonshotai.github.io/kimi-code/en/customization/mcp.html)
- [OpenCode V2 MCP](https://opencode.ai/v2/docs/mcp-servers)
- [MiMoCode](https://github.com/XiaomiMiMo/MiMo-Code)
