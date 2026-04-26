# jclaude

`jclaude` 是一个使用 Java 21 实现的 Claude Code 风格 CLI，命令名对标 `claude`。当前版本：`0.1.1`。当前通过 Java `HttpClient` 直连 provider 的 HTTP/SSE 接口，支持两种 API 格式：

- `anthropic`：Anthropic Messages API 格式，调用 `/v1/messages`，支持 SSE 流式输出和 tool use。
- `openai`：OpenAI-compatible Chat Completions 格式，调用 `/v1/chat/completions`，可用于 DeepSeek 等兼容 OpenAI API 的服务，支持 SSE 流式输出和 function tools。

## 环境要求

- Java 21+
- Maven 3.9+

## 当前能力概览

- `print` 模式和交互模式都支持远程模型 SSE 流式输出。
- provider 使用 ReAct 型 agent loop：模型流式输出文本，遇到工具调用就执行本地工具，再把结果送回模型继续下一轮。
- 支持 `Read` / `Bash` / `Write` / `Edit` / `Delete` 工具，其中 `Delete` 必须确认后才会真正删除。
- `Bash` 可执行编译、测试、启动服务等非交互命令，并返回 exit code、stdout 和 stderr。
- 支持可选 Plan Mode：复杂或高风险任务可以先只读分析和制定计划，用户批准后再允许修改。
- OpenAI-compatible provider 会保留并续传 DeepSeek 等模型返回的 `reasoning_content`，兼容 thinking mode 多轮工具调用。
- 交互模式会显示 `思考中...`、工具开始和工具结果状态，避免多轮工具调用时文本挤在同一行。
- 交互输入支持 `/` 命令补全、`@` 文件补全、光标左右移动、输入历史和长行横向滚动。
- 支持本地 skills、图片输入、配置文件和 Anthropic/OpenAI-compatible provider。

## 构建项目

```sh
mvn package
```

构建完成后会生成：

```sh
target/jclaude-0.1.1.jar
```

## 启动项目

### 方式一：使用启动脚本

```sh
./bin/jclaude --help
```

如果提示没有 jar 文件，先运行：

```sh
mvn package
```

### 方式二：直接运行 jar

```sh
java -jar target/jclaude-0.1.1.jar --help
```

## 基本用法

### 查看版本

```sh
./bin/jclaude --version
```

### 诊断环境

```sh
./bin/jclaude doctor
```

### 本地占位模式

未配置 API key 时，命令不会请求远程模型，会返回本地占位响应：

```sh
./bin/jclaude -p "你好"
```

### 交互模式

```sh
./bin/jclaude
```

交互模式会在当前进程内维护一份会话历史，实现多轮对话记忆。每次输入普通文本时，`jclaude` 会把当前会话中的 user / assistant 历史消息一起发送给模型，因此模型可以理解前文。

示例：

```text
jclaude> 请记住一个词：蓝鲸
已记住：蓝鲸
jclaude> 我刚才让你记住的词是什么？
你刚才让我记住的词是：蓝鲸
```

交互模式支持的 slash commands：

```text
/help      查看交互模式帮助
/status    查看当前工作目录、配置目录、provider 和 model
/config    查看配置文件路径
/model     查看当前模型
/skills    查看已加载的 skills
/plan      进入只读 Plan Mode；/plan status 查看状态；/plan off 手动退出
/clear     清空当前会话历史
/reset     清空当前会话历史
/exit      退出交互模式
```

注意：当前多轮记忆只保存在本次 `./bin/jclaude` 进程内；退出后不会持久化历史。`/clear` 和 `/reset` 会立即清空当前会话历史。

### 交互补全

交互模式支持类似 Claude Code 的输入提示：

- 输入 `/` 会自动显示内置 slash commands 和已加载 skills
- 继续输入命令前缀会过滤列表，例如 `/sk`
- 输入 `@` 会自动显示当前目录下的目录和文件
- 补全目录后会继续显示该目录下的目录和文件，例如 `@src/`
- 使用 `↑` / `↓` 选择候选项，使用 `Tab` 接受当前候选项
- 候选项未显示时，`↑` / `↓` 可浏览本次进程内输入历史
- 使用 `←` / `→` 可移动光标在中间修改；长输入会横向滚动，避免终端自动换行导致光标错位

### 本地文件读写

`jclaude` 会向模型提供类似 Claude Code 的本地文件工具：

- `Read`：读取文本文件或列出目录；文本读取默认最多返回 2000 行，支持 `offset` / `limit` 按行读取
- `Bash`：在当前工作目录执行非交互 shell 命令，返回 `exit_code`、`stdout` 和 `stderr`；默认超时 60 秒，最长 600 秒，输出会截断保护
- `Write`：创建或完整覆盖文件
- `Edit`：对已读文件做精确字符串替换
- `Delete`：删除文件或目录，执行前必须由用户显式确认
- `EnterPlanMode` / `ExitPlanMode`：可选计划模式；计划模式中禁止 `Bash`、写入、编辑和删除，提交计划并获用户批准后才退出

这些工具由模型在 ReAct 循环中调用，不是用户直接输入的 shell 命令。只有工具结果明确成功时，模型才应该告知用户“已读取/写入/编辑/删除”；如果工具失败或用户拒绝确认，操作不会执行。

`Bash` 适合让模型查看命令结果，例如：

```text
jclaude> 执行 mvn -q test，查看失败原因并修复
jclaude> 启动 Spring Boot 项目，若端口冲突请修复并验证接口
```

长期运行的服务建议让模型使用后台命令并把日志写入文件，例如 `mvn spring-boot:run > /tmp/app.log 2>&1 &`，再用 `curl` 或读取日志验证结果。

写入保护规则：

- `Write` / `Edit` 修改已有文件前必须先对该文件做完整 `Read`。
- 如果 `Read` 只读取了部分内容，或者文件在读取后被外部修改，`Write` / `Edit` 会拒绝执行。
- `Edit` 默认要求 `old_string` 唯一匹配；需要替换多处时必须设置 `replace_all=true`。
- 文本读取会拒绝过大的文件和明显的二进制文件。

输入中的 `@path` 会被解析为本地文件引用，支持相对路径、绝对路径和行范围：

```text
jclaude> @README.md 前3行内容
jclaude> @src/main/java/com/jclaude/cli/JClaude.java#L1-20 总结这段代码
```

为降低误写/误删风险，`Write` / `Edit` / `Delete` 默认只允许操作当前工作目录下的文件；如确需操作工作目录外路径，可设置：

```sh
export JCLAUDE_ALLOW_WRITE_OUTSIDE_CWD=true
```

`Delete` 属于破坏性操作：交互模式中会提示输入 `yes` 或 `确认` 后才会真正删除；`-p` 非交互模式默认不会执行删除。自动化场景如确需跳过确认，可显式设置：

```sh
export JCLAUDE_AUTO_CONFIRM_DESTRUCTIVE=true
```

### Plan Mode

Plan Mode 是可选的只读计划模式，适合复杂或高风险任务：

- 交互模式输入 `/plan` 可手动进入；`/plan status` 查看状态；`/plan off` 或 `/plan exit` 手动退出。
- 模型也可以调用 `EnterPlanMode` 进入计划模式。
- Plan Mode 中 `Bash` / `Write` / `Edit` / `Delete` 会被拒绝，只允许读取、分析和制定计划。
- 模型调用 `ExitPlanMode` 时必须提交 `plan`，交互模式会展示计划并要求用户输入 `yes` 或 `确认`。
- `-p` 非交互模式默认不会自动批准计划；自动化场景可设置 `JCLAUDE_AUTO_APPROVE_PLAN=true`。

### 图片输入

`jclaude` 支持在 prompt 中直接传入本地图片路径，路径会作为图片内容发送给支持视觉能力的模型。

支持格式：`.png`、`.jpg`、`.jpeg`、`.gif`、`.webp`。

当 provider 为 `openai` 时，`.webp` 图片会先在本地临时转换为 PNG，再按 OpenAI-compatible `image_url` 格式发送。这样可以兼容部分 OpenAI-compatible 服务（例如 LM Studio）对 WebP data URL 支持不完整的问题。

示例：

```sh
./bin/jclaude -p "/Users/huanglei/Pictures/example.webp 描述一下这张图片的内容"
./bin/jclaude -p "@./screenshots/app.png 这张图里有什么问题？"
```

交互模式中也可以直接输入：

```text
jclaude> /Users/huanglei/Pictures/example.webp 描述一下这张图片的内容
```

如果路径中包含空格，可以使用引号：

```text
jclaude> "@/Users/huanglei/Pictures/my image.png" 描述一下这张图片
```

## Skills

`jclaude` 支持类似 Claude Code 的本地 skills：每个 skill 是一个目录，目录内包含 `SKILL.md`。

支持的目录：

- 当前项目向上查找：`.jclaude/skills/<skill-name>/SKILL.md`
- Claude Code 兼容路径：`.claude/skills/<skill-name>/SKILL.md`
- 用户级路径：`~/.jclaude/skills/<skill-name>/SKILL.md`
- Claude Code 用户级兼容路径：`~/.claude/skills/<skill-name>/SKILL.md`
- 额外路径：`--add-dir <dir>` 会扫描 `<dir>/.jclaude/skills` 和 `<dir>/.claude/skills`

示例：

```sh
mkdir -p .jclaude/skills/commit
cat > .jclaude/skills/commit/SKILL.md <<'MD'
---
description: Generate a concise git commit message
when_to_use: Use when the user asks to prepare or review a commit
argument-hint: "<scope>"
arguments: scope
---
# Commit Skill

Review the current change summary and write a concise commit message for $scope.
MD
```

查看已加载 skills：

```sh
./bin/jclaude
jclaude> /skills
```

直接调用 skill：

```sh
./bin/jclaude -p "/commit auth"
```

交互模式也可以直接输入：

```text
jclaude> /commit auth
```

`SKILL.md` 支持的常用 frontmatter：

- `description`：skill 描述，用于列表和系统提示
- `when_to_use`：什么时候使用该 skill
- `argument-hint`：调用参数提示
- `arguments`：命名参数，例如 `scope topic`
- `user-invocable`：设为 `false` 时禁止用户直接 `/skill` 调用
- `disable-model-invocation`：设为 `true` 时禁用该 skill 调用

正文中支持以下占位符：

- `$ARGUMENTS`：完整参数字符串
- `$ARGUMENTS[0]` / `$0`：第 1 个参数
- `$name`：`arguments` 中定义的命名参数
- `${CLAUDE_SKILL_DIR}` / `${JCLAUDE_SKILL_DIR}`：当前 skill 目录
- `${CLAUDE_SESSION_ID}` / `${JCLAUDE_SESSION_ID}`：当前会话 ID

## 使用 Anthropic / Claude

### 通过环境变量配置

```sh
export ANTHROPIC_API_KEY="你的 Anthropic API Key"
export ANTHROPIC_MODEL="claude-opus-4-7"
./bin/jclaude -p --provider anthropic "你好"
```

也可以用统一变量：

```sh
export JCLAUDE_PROVIDER="anthropic"
export JCLAUDE_MODEL="claude-opus-4-7"
export JCLAUDE_API_KEY="你的 API Key"
./bin/jclaude -p "你好"
```

### 指定模型

```sh
./bin/jclaude -p --provider anthropic --model claude-sonnet-4-6 "你好"
```

### 使用 Anthropic-compatible endpoint

如果某个服务提供 Anthropic Messages API 兼容格式，可以通过 `--base-url` 指向它：

```sh
JCLAUDE_API_KEY="你的 API Key" \
./bin/jclaude -p \
  --provider anthropic \
  --base-url https://example.com \
  --model claude-opus-4-7 \
  "你好"
```

`--base-url` 可以是 base URL，也可以是完整 endpoint：

```sh
--base-url https://example.com
--base-url https://example.com/v1
--base-url https://example.com/v1/messages
```

## 使用 OpenAI-compatible API

`openai` provider 使用 `/v1/chat/completions` 格式。

### OpenAI-compatible 默认用法

```sh
export OPENAI_API_KEY="你的 API Key"
export OPENAI_MODEL="gpt-4.1"
./bin/jclaude -p --provider openai "你好"
```

也可以使用统一变量：

```sh
export JCLAUDE_PROVIDER="openai"
export JCLAUDE_MODEL="gpt-4.1"
export JCLAUDE_API_KEY="你的 API Key"
./bin/jclaude -p "你好"
```

### 使用 DeepSeek

DeepSeek 使用 OpenAI-compatible API 格式，因此配置到 `openai` provider：

```sh
OPENAI_API_KEY="你的 DeepSeek API Key" \
./bin/jclaude -p \
  --provider openai \
  --base-url https://api.deepseek.com \
  --model deepseek-chat \
  "你好"
```

也可以用环境变量：

```sh
export JCLAUDE_PROVIDER="openai"
export JCLAUDE_BASE_URL="https://api.deepseek.com"
export JCLAUDE_MODEL="deepseek-chat"
export OPENAI_API_KEY="你的 DeepSeek API Key"
./bin/jclaude -p "你好"
```

## 配置文件

`jclaude` 支持类似 Claude Code 的配置文件层级。推荐把非敏感配置写入 JSON 文件，把 API key 继续放在环境变量中。

配置文件读取顺序从低到高：

1. `~/.jclaude/settings.json`
2. `~/.jclaude/settings.local.json`
3. 当前项目 `.jclaude/settings.json`
4. 当前项目 `.jclaude/settings.local.json`

示例：

```json
{
  "provider": "openai",
  "model": "deepseek-chat",
  "baseUrl": "https://api.deepseek.com",
  "outputFormat": "text",
  "apiKeyEnv": "OPENAI_API_KEY"
}
```

### `apiKeyEnv`：推荐方式

`apiKeyEnv` 写的是“环境变量名”，不是 API key 本身。`jclaude` 会读取这个环境变量的值作为 API key。

项目级配置示例：

```sh
mkdir -p .jclaude
cat > .jclaude/settings.json <<'JSON'
{
  "provider": "openai",
  "model": "deepseek-chat",
  "baseUrl": "https://api.deepseek.com",
  "outputFormat": "text",
  "apiKeyEnv": "OPENAI_API_KEY"
}
JSON

OPENAI_API_KEY="你的 DeepSeek API Key" ./bin/jclaude -p "你好"
```

常见错误：不要把真实 key 写到 `apiKeyEnv` 里。

```json
{
  "apiKeyEnv": "sk-xxxx"
}
```

上面表示读取名为 `sk-xxxx` 的环境变量，不会把 `sk-xxxx` 当作 key 使用。

### `apiKey`：直接写入 key

如果你希望不额外设置环境变量，也可以直接在配置文件中写 `apiKey`：

```json
{
  "provider": "anthropic",
  "model": "cc-gpt-5.5",
  "baseUrl": "https://api.codexzh.com",
  "outputFormat": "text",
  "apiKey": "你的 API Key"
}
```

这种方式启动最方便：

```sh
./bin/jclaude
./bin/jclaude -p "你好"
```

注意：`apiKey` 会以明文形式保存在配置文件中。只建议用于个人本机配置，不建议提交到 git。

用户级配置示例：

```sh
mkdir -p ~/.jclaude
cat > ~/.jclaude/settings.json <<'JSON'
{
  "provider": "anthropic",
  "model": "claude-opus-4-7",
  "outputFormat": "text"
}
JSON
```

支持字段：

- `provider`：`anthropic` 或 `openai`
- `model`：模型名
- `baseUrl` / `base_url`：provider base URL 或完整 endpoint
- `outputFormat` / `output_format`：`text`、`json` 或 `stream-json`
- `apiKey` / `api_key`：直接写入 API key，适合个人本机快速启动，但会明文保存
- `apiKeyEnv` / `api_key_env`：写环境变量名，`jclaude` 会从该环境变量读取 API key，推荐使用

### 常用环境变量

| 环境变量 | 作用 |
| --- | --- |
| `JCLAUDE_CONFIG_DIR` / `CLAUDE_CONFIG_DIR` | 覆盖用户级配置目录，默认是 `~/.jclaude` |
| `JCLAUDE_PROVIDER` | 默认 provider：`anthropic` 或 `openai` |
| `JCLAUDE_MODEL` | 统一模型名覆盖 |
| `ANTHROPIC_MODEL` / `OPENAI_MODEL` | provider 专属模型名 |
| `JCLAUDE_API_KEY` | 统一 API key 兜底变量 |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | provider 专属 API key，优先级高于 `JCLAUDE_API_KEY` |
| `JCLAUDE_BASE_URL` | 统一 base URL 覆盖 |
| `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL` | provider 专属 base URL |
| `JCLAUDE_OUTPUT_FORMAT` | 默认输出格式：`text`、`json` 或 `stream-json` |
| `JCLAUDE_ALLOW_WRITE_OUTSIDE_CWD` | 设为 `true` 或 `1` 后允许写入/编辑/删除当前工作目录外路径 |
| `JCLAUDE_AUTO_CONFIRM_DESTRUCTIVE` | 设为 `true` 或 `1` 后跳过 `Delete` 确认，仅建议自动化场景使用 |
| `JCLAUDE_AUTO_APPROVE_PLAN` | 设为 `true` 或 `1` 后允许非交互模式自动批准 `ExitPlanMode` |

## Agent 循环与流式输出

远程 provider 都走同一类 ReAct 型 agent loop：

1. 构造包含系统提示、会话历史和工具定义的请求，并设置 `stream=true`。
2. 通过 SSE 逐条读取 `data:` JSON 事件，文本 delta 会立即输出给终端或 JSONL。
3. 如果模型发起工具调用，`jclaude` 执行对应本地工具，并把工具结果追加回对话。
4. 继续下一轮流式请求，直到模型不再调用工具或达到最大工具轮次。

这不是固定的 PAE（Plan-Act-Execute）流水线；默认是 ReAct 循环。Plan Mode 是额外的可选权限模式，用于在行动前先提交计划并等待用户批准。

交互模式会把模型思考和工具调用转换成简短状态行：

- 收到 Anthropic `thinking_delta` 或 OpenAI-compatible `reasoning_content` 时显示 `思考中...`，但不会打印思考内容。
- 工具开始执行时显示 `→ 工具名: 摘要`，例如 `→ Bash: mvn spring-boot:run`。
- 工具结束后显示结果摘要，例如 `✓ Bash exit 0` 或 `✗ Bash exit 1`。

OpenAI-compatible provider 的 thinking mode 会保留 `reasoning_content` 并在后续工具轮次回传给 API；这对 DeepSeek 等要求续传 reasoning 的模型是必需的。

## 输出格式

支持三种输出格式：

```sh
./bin/jclaude -p --output-format text "你好"
./bin/jclaude -p --output-format json "你好"
./bin/jclaude -p --output-format stream-json "你好"
```

说明：

- `text`：默认格式，连接远程模型时会流式输出文本
- `json`：仍使用同一套 SSE/ReAct 循环，但 CLI 会缓冲完整响应后输出单个 JSON 对象
- `stream-json`：通过 SSE 按真实增量输出 JSONL 事件；如果模型调用工具，会在工具结果返回后继续下一轮增量输出

## 配置优先级

### Provider

优先级从高到低：

1. `--provider`
2. `JCLAUDE_PROVIDER`
3. 配置文件中的 `provider`
4. 默认值：`anthropic`

可选值：

- `anthropic`
- `openai`

### Model

优先级从高到低：

1. `--model`
2. `JCLAUDE_MODEL`
3. provider 专属环境变量：`ANTHROPIC_MODEL` 或 `OPENAI_MODEL`
4. 配置文件中的 `model`
5. 默认值：
   - `anthropic`：`claude-opus-4-7`
   - `openai`：`gpt-4.1`

### API Key

`anthropic` provider：

1. `ANTHROPIC_API_KEY`
2. `JCLAUDE_API_KEY`

`openai` provider：

1. `OPENAI_API_KEY`
2. `JCLAUDE_API_KEY`

### Base URL

优先级从高到低：

1. `--base-url`
2. `JCLAUDE_BASE_URL`
3. provider 专属环境变量：`ANTHROPIC_BASE_URL` 或 `OPENAI_BASE_URL`
4. 配置文件中的 `baseUrl` 或 `base_url`
5. 默认值：
   - `anthropic`：`https://api.anthropic.com`
   - `openai`：`https://api.openai.com`

## 当前限制

- 交互模式历史只保存在当前进程内，暂不支持跨进程 `/continue` 或 `/resume` 持久恢复。
- `--input-format`、`--allowed-tools`、`--disallowed-tools`、`--mcp-config`、`--permission-mode`、`--settings`、`--agents` 当前只完成参数解析或 help 占位，尚未真正接入对应能力。
- `mcp`、`plugin`、`auth/login/logout`、`install`、`update` 仍是占位实现；`completion` 当前只输出通用提示。
- 暂未实现 MCP 工具、插件管理、工具 allowlist/denylist、权限模式矩阵和会话持久化。
- `Bash` 是非交互命令执行工具，不适合需要持续前台交互输入的程序；长时间运行的服务应后台启动并写日志。
- 工具循环有最大轮次保护，超过后会中止并报错，避免模型无限调用工具。
- 图片会作为本地文件内容发送给 provider，但最终识别效果取决于所选模型是否支持视觉输入。
- OpenAI-compatible provider 的 WebP 图片会依赖本机 `sips` 命令临时转换为 PNG；非 macOS 环境如缺少 `sips`，请先手动转换为 PNG/JPEG。
