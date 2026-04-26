package com.jclaude.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JClaude {
    private static final String VERSION = "0.1.1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final Set<String> KNOWN_SUBCOMMANDS = Set.of(
            "auth", "completion", "config", "doctor", "help", "install", "login", "logout", "mcp", "plugin", "update"
    );
    private static final int MAX_SKILL_LISTING_DESCRIPTION_CHARS = 250;
    private static final int SKILL_LISTING_CHAR_BUDGET = 8_000;
    private static final String SESSION_ID = "jclaude-" + UUID.randomUUID();
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*\\R?(.*)\\z", Pattern.DOTALL);
    private static final int MAX_TOOL_TURNS = 20;
    private static final int MAX_TOOL_READ_LINES = 2_000;
    private static final long MAX_TOOL_TEXT_FILE_BYTES = 2_000_000L;
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int MAX_COMMAND_TIMEOUT_SECONDS = 600;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 200_000;
    private static final Pattern AT_FILE_REFERENCE_PATTERN = Pattern.compile("(^|\\s)@(?:\"([^\"]+)\"|([^\\s]+))");
    private static final Pattern AT_FILE_LINE_RANGE_PATTERN = Pattern.compile("^([^#]+)(?:#L(\\d+)(?:-(\\d+))?)?(?:#[^#]*)?$");

    public static void main(String[] args) {
        int exitCode = new JClaude().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        try {
            CliRequest request = CliParser.parse(args);
            if (request.help()) {
                printHelp();
                return 0;
            }
            if (request.version()) {
                System.out.println("jclaude " + VERSION);
                return 0;
            }
            if (request.subcommand().isPresent()) {
                return handleSubcommand(request);
            }
            if (request.printMode()) {
                return runPrintMode(request);
            }
            return runInteractive(request);
        } catch (CliException exception) {
            System.err.println("jclaude: " + exception.getMessage());
            System.err.println("Try 'jclaude --help' for usage.");
            return 2;
        } catch (IOException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            System.err.println("jclaude: " + message);
            return 1;
        }
    }

    private int handleSubcommand(CliRequest request) throws IOException {
        String subcommand = request.subcommand().orElseThrow();
        return switch (subcommand) {
            case "help" -> {
                printHelp();
                yield 0;
            }
            case "doctor" -> runDoctor(request);
            case "config" -> runConfig(request);
            case "login", "logout", "auth" -> explainStub(subcommand, "Authentication is not wired yet. Set ANTHROPIC_API_KEY for print mode.");
            case "mcp" -> explainStub(subcommand, "MCP server management is not implemented in this Java prototype.");
            case "plugin" -> explainStub(subcommand, "Plugin management is not implemented in this Java prototype.");
            case "completion" -> runCompletion(request);
            case "install" -> explainStub(subcommand, "Build with 'mvn package' and add ./bin to PATH.");
            case "update" -> explainStub(subcommand, "Update by rebuilding this project from source.");
            default -> throw new CliException("Unknown command: " + subcommand);
        };
    }

    private int runPrintMode(CliRequest request) throws IOException {
        String prompt = request.prompt().isBlank() ? readStdin() : request.prompt();
        if (prompt.isBlank()) {
            throw new CliException("No prompt supplied. Pass a prompt, pipe stdin, or start interactive mode without --print.");
        }

        EffectiveConfig config = effectiveConfig(request);
        AgentSession agentSession = new AgentSession();
        prompt = config.skills().resolveInvocation(prompt)
                .map(SkillInvocation::expandedPrompt)
                .orElse(prompt);
        PromptInput promptInput = PromptInput.parse(prompt);
        String outputFormat = config.outputFormat();
        String model = config.model();
        if ("json".equals(outputFormat)) {
            String response = responseFor(List.of(promptInput.toChatMessage()), config, false);
            System.out.println(toJson(Map.of(
                    "type", "assistant_message",
                    "model", model,
                    "content", response
            )));
        } else if ("stream-json".equals(outputFormat)) {
            System.out.println(toJson(Map.of("type", "message_start", "model", model)));
            responseForStreaming(
                    List.of(promptInput.toChatMessage()),
                    config,
                    agentSession,
                    false,
                    (Consumer<String>) chunk -> System.out.println(toJson(Map.of("type", "content_block_delta", "text", chunk)))
            );
            System.out.println(toJson(Map.of("type", "message_stop")));
        } else if ("text".equals(outputFormat)) {
            responseForStreaming(
                    List.of(promptInput.toChatMessage()),
                    config,
                    agentSession,
                    false,
                    (Consumer<String>) chunk -> {
                        System.out.print(chunk);
                        System.out.flush();
                    }
            );
            System.out.println();
        } else {
            throw new CliException("Unsupported output format: " + outputFormat);
        }
        return 0;
    }

    private int runInteractive(CliRequest request) throws IOException {
        EffectiveConfig config = effectiveConfig(request);
        List<ChatMessage> history = new ArrayList<>();
        AgentSession agentSession = new AgentSession();
        printBanner(config);
        try (InteractiveLineReader reader = InteractiveLineReader.open(config.skills())) {
            while (true) {
                String line = reader.readLine("jclaude> ");
                if (line == null || "/exit".equals(line.trim()) || "/quit".equals(line.trim())) {
                    System.out.println("Goodbye.");
                    return 0;
                }
                if (line.isBlank()) {
                    continue;
                }
                PromptInput promptInput = PromptInput.parse(line);
                if (line.startsWith("/") && promptInput.images().isEmpty()) {
                    if (handleSlashCommand(line.trim(), config, history, agentSession)) {
                        continue;
                    }
                    Optional<SkillInvocation> invocation;
                    try {
                        invocation = config.skills().resolveInvocation(line);
                    } catch (CliException exception) {
                        System.out.println(exception.getMessage());
                        continue;
                    }
                    if (invocation.isEmpty()) {
                        System.out.println("Unknown slash command or skill. Try /help or /skills.");
                        continue;
                    }
                    System.out.println("Running skill: " + invocation.get().skill().name());
                    List<ChatMessage> nextMessages = new ArrayList<>(history);
                    nextMessages.add(new ChatMessage("user", invocation.get().expandedPrompt()));
                    InteractiveStreamingObserver observer = new InteractiveStreamingObserver();
                    String response = responseForStreaming(
                            nextMessages,
                            config,
                            agentSession,
                            true,
                            observer
                    );
                    observer.finish();
                    history.clear();
                    history.addAll(nextMessages);
                    history.add(new ChatMessage("assistant", response));
                    continue;
                }
                List<ChatMessage> nextMessages = new ArrayList<>(history);
                nextMessages.add(promptInput.toChatMessage());
                InteractiveStreamingObserver observer = new InteractiveStreamingObserver();
                String response = responseForStreaming(
                        nextMessages,
                        config,
                        agentSession,
                        true,
                        observer
                );
                observer.finish();
                history.clear();
                history.addAll(nextMessages);
                history.add(new ChatMessage("assistant", response));
            }
        }
    }

    private boolean handleSlashCommand(String command, EffectiveConfig config, List<ChatMessage> history, AgentSession agentSession) throws IOException {
        switch (command) {
            case "/help" -> {
                printInteractiveHelp();
                return true;
            }
            case "/status" -> {
                printStatus(config);
                return true;
            }
            case "/config" -> {
                printConfigPaths();
                return true;
            }
            case "/model" -> {
                System.out.println("Model: " + config.model());
                return true;
            }
            case "/skills" -> {
                printSkills(config.skills());
                return true;
            }
            case "/clear", "/reset" -> {
                history.clear();
                System.out.println("Conversation history cleared.");
                return true;
            }
            case "/plan" -> {
                agentSession.enterPlanMode();
                System.out.println("已进入 Plan Mode：我会先只读取/分析并制定计划，未经批准不会写入、编辑或删除文件。");
                return true;
            }
            case "/plan status" -> {
                System.out.println("Plan Mode: " + (agentSession.isPlanMode() ? "on" : "off"));
                agentSession.lastPlan().ifPresent(plan -> System.out.println("Last plan:\n" + plan));
                return true;
            }
            case "/plan off", "/plan exit" -> {
                agentSession.exitPlanMode();
                System.out.println("已退出 Plan Mode。");
                return true;
            }
            default -> {
                if (command.startsWith("/skills ")) {
                    printSkills(config.skills());
                    return true;
                }
                return false;
            }
        }
    }

    private int runDoctor(CliRequest request) throws IOException {
        EffectiveConfig config = effectiveConfig(request);
        System.out.println("jclaude doctor");
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        printConfigPaths();
        System.out.println("Provider: " + config.provider().id());
        System.out.println("Model: " + config.model());
        config.baseUrl().ifPresent(value -> System.out.println("Base URL: " + value));
        System.out.println("Output format: " + config.outputFormat());
        System.out.println("API key: " + (config.apiKey().isPresent() ? "set" : "not set"));
        System.out.println("Skills: " + config.skills().size());
        System.out.println("ANTHROPIC_API_KEY: " + (System.getenv("ANTHROPIC_API_KEY") == null ? "not set" : "set"));
        System.out.println("OPENAI_API_KEY: " + (System.getenv("OPENAI_API_KEY") == null ? "not set" : "set"));
        return 0;
    }

    private int runConfig(CliRequest request) throws IOException {
        if (request.positionals().contains("paths")) {
            printConfigPaths();
            return 0;
        }
        System.out.println("Config commands available: paths");
        printConfigPaths();
        return 0;
    }

    private int runCompletion(CliRequest request) {
        String shell = request.positionals().stream().skip(1).findFirst().orElse("sh");
        if (!Set.of("bash", "zsh", "fish", "sh").contains(shell)) {
            throw new CliException("Unsupported shell for completion: " + shell);
        }
        System.out.println("# Add ./bin to PATH, then complete jclaude with your shell's standard file completion.");
        return 0;
    }

    private int explainStub(String command, String detail) {
        System.out.println("jclaude " + command + ": " + detail);
        return 0;
    }

    private void printBanner(EffectiveConfig config) throws IOException {
        System.out.println("jclaude " + VERSION + " — Java 21 CLI inspired by Claude Code");
        System.out.println("Type /help for commands, /exit to quit.");
        printStatus(config);
    }

    private void printInteractiveHelp() {
        System.out.println("Slash commands: /help, /status, /config, /model, /skills, /plan, /clear, /reset, /exit");
        System.out.println("Autocomplete: type / for commands and skills, @ for files; use ↑/↓ to select and Tab to accept.");
        System.out.println("Editing: use ←/→ to move the cursor, Home/End or Ctrl+A/Ctrl+E to jump, ↑/↓ to browse input history when autocomplete is closed.");
        System.out.println("Plan Mode: /plan enters read-only planning; /plan status shows state; /plan off exits manually.");
    }

    private void printStatus(EffectiveConfig config) throws IOException {
        System.out.println("Working directory: " + Path.of("").toAbsolutePath().normalize());
        System.out.println("Config directory: " + configDir());
        System.out.println("Provider: " + config.provider().id());
        System.out.println("Model: " + config.model());
        config.baseUrl().ifPresent(value -> System.out.println("Base URL: " + value));
        System.out.println("Skills: " + config.skills().size());
    }

    private void printConfigPaths() throws IOException {
        Path configDir = configDir();
        Path projectSettings = Path.of(".jclaude", "settings.json").toAbsolutePath().normalize();
        Path localSettings = Path.of(".jclaude", "settings.local.json").toAbsolutePath().normalize();
        Files.createDirectories(configDir);
        System.out.println("Config directory: " + configDir);
        System.out.println("Global config: " + configDir.resolve(".config.json"));
        System.out.println("Project settings: " + projectSettings);
        System.out.println("Local settings: " + localSettings);
        System.out.println("Global skills: " + configDir.resolve("skills"));
        System.out.println("Claude-compatible global skills: " + Path.of(System.getProperty("user.home"), ".claude", "skills").toAbsolutePath().normalize());
        System.out.println("Project skills: " + Path.of(".jclaude", "skills").toAbsolutePath().normalize());
        System.out.println("Claude-compatible project skills: " + Path.of(".claude", "skills").toAbsolutePath().normalize());
    }

    private void printSkills(SkillRegistry skills) {
        if (skills.isEmpty()) {
            System.out.println("No skills loaded.");
            System.out.println("Create skills in .jclaude/skills/<name>/SKILL.md or ~/.jclaude/skills/<name>/SKILL.md.");
            return;
        }
        System.out.println(skills.describeForHumans());
    }

    private Path configDir() {
        String override = System.getenv("JCLAUDE_CONFIG_DIR");
        if (override == null || override.isBlank()) {
            override = System.getenv("CLAUDE_CONFIG_DIR");
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".jclaude").toAbsolutePath().normalize();
    }

    private EffectiveConfig effectiveConfig(CliRequest request) throws IOException {
        Map<String, String> fileConfig = loadFileConfig();
        Provider provider = Provider.parse(firstNonBlank(
                request.option("provider").orElse(null),
                System.getenv("JCLAUDE_PROVIDER"),
                fileConfig.get("provider"),
                "anthropic"
        ));
        String model = firstNonBlank(
                request.option("model").orElse(null),
                System.getenv("JCLAUDE_MODEL"),
                provider == Provider.ANTHROPIC ? System.getenv("ANTHROPIC_MODEL") : System.getenv("OPENAI_MODEL"),
                fileConfig.get("model"),
                defaultModel(provider)
        );
        Optional<String> baseUrl = Optional.ofNullable(firstNonBlank(
                request.option("base-url").orElse(null),
                System.getenv("JCLAUDE_BASE_URL"),
                provider == Provider.ANTHROPIC ? System.getenv("ANTHROPIC_BASE_URL") : System.getenv("OPENAI_BASE_URL"),
                fileConfig.get("baseUrl"),
                fileConfig.get("base_url")
        ));
        String outputFormat = firstNonBlank(
                request.option("output-format").orElse(null),
                System.getenv("JCLAUDE_OUTPUT_FORMAT"),
                fileConfig.get("outputFormat"),
                fileConfig.get("output_format"),
                "text"
        );
        Optional<String> apiKey = Optional.ofNullable(firstNonBlank(
                envValue(fileConfig.get("apiKeyEnv")),
                envValue(fileConfig.get("api_key_env")),
                apiKey(provider),
                fileConfig.get("apiKey"),
                fileConfig.get("api_key")
        ));
        SkillRegistry skills = SkillRegistry.load(configDir(), request);
        return new EffectiveConfig(provider, model, baseUrl, outputFormat, apiKey, skills);
    }

    private Map<String, String> loadFileConfig() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        mergeConfig(values, configDir().resolve("settings.json"));
        mergeConfig(values, configDir().resolve("settings.local.json"));
        mergeConfig(values, Path.of(".jclaude", "settings.json").toAbsolutePath().normalize());
        mergeConfig(values, Path.of(".jclaude", "settings.local.json").toAbsolutePath().normalize());
        return values;
    }

    private void mergeConfig(Map<String, String> values, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        JsonNode node = JSON.readTree(path.toFile());
        if (!node.isObject()) {
            throw new CliException("Config file must contain a JSON object: " + path);
        }
        putText(values, node, "provider");
        putText(values, node, "model");
        putText(values, node, "baseUrl");
        putText(values, node, "base_url");
        putText(values, node, "outputFormat");
        putText(values, node, "output_format");
        putText(values, node, "apiKey");
        putText(values, node, "api_key");
        putText(values, node, "apiKeyEnv");
        putText(values, node, "api_key_env");
    }

    private void putText(Map<String, String> values, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            values.put(key, value.asText());
        }
    }

    private String defaultModel(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> "claude-opus-4-7";
            case OPENAI -> "gpt-4.1";
        };
    }

    private String responseFor(String prompt, EffectiveConfig config) throws IOException {
        return responseFor(List.of(PromptInput.parse(prompt).toChatMessage()), config, false);
    }

    private String responseFor(List<ChatMessage> messages, EffectiveConfig config, boolean allowDestructiveConfirmation) throws IOException {
        return responseFor(messages, config, new AgentSession(), allowDestructiveConfirmation);
    }

    private String responseFor(List<ChatMessage> messages, EffectiveConfig config, AgentSession agentSession, boolean allowDestructiveConfirmation) throws IOException {
        StringBuilder response = new StringBuilder();
        responseForStreaming(messages, config, agentSession, allowDestructiveConfirmation, (Consumer<String>) response::append);
        return response.toString();
    }

    private String responseForStreaming(List<ChatMessage> messages, EffectiveConfig config, AgentSession agentSession, boolean allowDestructiveConfirmation, Consumer<String> onText) throws IOException {
        return responseForStreaming(messages, config, agentSession, allowDestructiveConfirmation, StreamingObserver.forText(onText));
    }

    private String responseForStreaming(List<ChatMessage> messages, EffectiveConfig config, AgentSession agentSession, boolean allowDestructiveConfirmation, StreamingObserver observer) throws IOException {
        if (config.apiKey().isEmpty()) {
            String response = localResponse(messages.getLast(), config.provider(), config.skills());
            observer.onText(response);
            return response;
        }
        return switch (config.provider()) {
            case ANTHROPIC -> anthropicStreamingToolLoop(
                    messages,
                    config.model(),
                    config.apiKey().orElseThrow(),
                    config.baseUrl().orElseGet(() -> baseUrl(config.provider())),
                    config.skills(),
                    agentSession,
                    allowDestructiveConfirmation,
                    observer
            );
            case OPENAI -> openAiStreamingToolLoop(
                    messages,
                    config.model(),
                    config.apiKey().orElseThrow(),
                    config.baseUrl().orElseGet(() -> baseUrl(config.provider())),
                    config.skills(),
                    agentSession,
                    allowDestructiveConfirmation,
                    observer
            );
        };
    }

    private String systemPrompt(SkillRegistry skills, AgentSession agentSession) {
        String cwd = Path.of("").toAbsolutePath().normalize().toString();
        String filesystemPrompt = """
                # 语言
                默认使用用户最近一条消息的语言回复；如果用户使用中文，必须用中文回复，除非用户明确要求使用其他语言。

                # Agent 循环
                你以 ReAct（Reasoning + Acting）方式工作：先判断下一步，必要时调用工具；读取工具结果后继续推理和行动；不要声称已经执行未通过工具完成的操作。
                可以多轮调用工具，直到拥有足够证据再回答用户。

                # Plan Mode
                当前权限模式：%s。
                对复杂或高风险改动，可以调用 EnterPlanMode 进入计划模式；用户也可能通过 /plan 主动进入计划模式。
                在 Plan Mode 中只能读取、搜索和制定计划，不能写入、编辑或删除文件。
                计划完成后调用 ExitPlanMode，并在 plan 参数中给出清晰、可执行的计划；只有用户批准后才会退出 Plan Mode 并允许修改文件。

                # 本地文件系统
                你正在 jclaude CLI 中运行。当前工作目录：%s

                你可以用 Read、Bash、Write、Edit、Delete 工具检查、运行命令和修改本地文件，并可用 EnterPlanMode / ExitPlanMode 管理计划模式。
                当用户提到 @path 时，将其视为本地文件引用；相对路径从当前工作目录解析。
                优先用 Read 查看文件，用 Edit 做定点修改，只有创建新文件或完整重写时才用 Write。
                修改已有文件前必须先读取文件，确保掌握最新内容。
                删除文件必须使用 Delete 工具；不要用文字声称删除，也不要用 Write/Edit 或 Bash 伪造删除。
                需要编译、测试、启动服务或查看命令结果时，使用 Bash；Bash 返回 exit code、stdout 和 stderr。长期运行的服务应后台启动并把日志写入文件，或设置合理超时只捕获启动报错。
                只有当工具结果明确成功时，才能告诉用户文件已读取、写入、修改或删除。如果工具返回错误或用户拒绝确认，必须明确说明操作没有执行。
                Delete 是破坏性操作，jclaude 会在执行前请求用户显式确认。
                在 Plan Mode 中 Bash/Write/Edit/Delete 会被拒绝。
                """.formatted(agentSession.modeLabel(), cwd);
        String skillPrompt = skills.systemPrompt();
        if (skillPrompt.isBlank()) {
            return filesystemPrompt;
        }
        return filesystemPrompt + System.lineSeparator() + skillPrompt;
    }

    private String anthropicStreamingToolLoop(
            List<ChatMessage> messages,
            String model,
            String apiKey,
            String baseUrl,
            SkillRegistry skills,
            AgentSession agentSession,
            boolean allowDestructiveConfirmation,
            StreamingObserver observer
    ) throws IOException {
        List<Map<String, Object>> payloadMessages = new ArrayList<>(anthropicMessagePayload(messages));
        ToolSession toolSession = new ToolSession(allowDestructiveConfirmation, agentSession);
        StringBuilder fullResponse = new StringBuilder();
        for (int turn = 0; turn < MAX_TOOL_TURNS; turn++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 16_000);
            body.put("cache_control", Map.of("type", "ephemeral"));
            body.put("stream", true);
            body.put("tools", anthropicToolDefinitions());
            String systemPrompt = systemPrompt(skills, agentSession);
            if (!systemPrompt.isBlank()) {
                body.put("system", systemPrompt);
            }
            if ("claude-opus-4-7".equals(model)) {
                body.put("thinking", Map.of("type", "adaptive"));
            }
            body.put("messages", payloadMessages);

            StreamingAnthropicTurn streamingTurn = anthropicStreamingToolTurn(
                    apiUrl(baseUrl, "/v1/messages"),
                    body,
                    Map.of(
                            "x-api-key", apiKey,
                            "anthropic-version", "2023-06-01"
                    ),
                    observer
            );
            fullResponse.append(streamingTurn.text());
            if (streamingTurn.toolCalls().isEmpty()) {
                return fullResponse.toString();
            }

            payloadMessages.add(Map.of(
                    "role", "assistant",
                    "content", streamingTurn.contentBlocks()
            ));
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (ToolCall toolCall : streamingTurn.toolCalls()) {
                observer.onToolStart(toolCall);
                String result = toolSession.execute(toolCall.name(), toolCall.input());
                observer.onToolEnd(toolCall, result);
                toolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", toolCall.id(),
                        "content", result
                ));
            }
            payloadMessages.add(Map.of("role", "user", "content", toolResults));
        }
        throw new CliException("Tool loop exceeded maximum turns (" + MAX_TOOL_TURNS + ")");
    }

    private StreamingAnthropicTurn anthropicStreamingToolTurn(
            String url,
            Map<String, Object> body,
            Map<String, String> headers,
            StreamingObserver observer
    ) throws IOException {
        StringBuilder text = new StringBuilder();
        Map<Integer, Map<String, Object>> contentBlocks = new TreeMap<>();
        Map<Integer, StringBuilder> toolInputBuffers = new LinkedHashMap<>();
        List<ToolCall> toolCalls = new ArrayList<>();

        sendSseJsonEvents(url, body, headers, json -> {
            String type = json.path("type").asText();
            if ("content_block_start".equals(type)) {
                int index = json.path("index").asInt();
                JsonNode contentBlock = json.path("content_block");
                Map<String, Object> block = mutableMap(contentBlock);
                String blockType = contentBlock.path("type").asText();
                if ("text".equals(blockType)) {
                    block.put("text", "");
                } else if ("tool_use".equals(blockType)) {
                    block.put("input", Map.of());
                    toolInputBuffers.put(index, new StringBuilder());
                } else if ("thinking".equals(blockType)) {
                    block.put("thinking", "");
                    block.putIfAbsent("signature", "");
                }
                contentBlocks.put(index, block);
                return false;
            }
            if ("content_block_delta".equals(type)) {
                int index = json.path("index").asInt();
                Map<String, Object> block = contentBlocks.get(index);
                if (block == null) {
                    return false;
                }
                JsonNode delta = json.path("delta");
                switch (delta.path("type").asText()) {
                    case "text_delta" -> {
                        String chunk = delta.path("text").asText("");
                        if (!chunk.isEmpty()) {
                            block.put("text", block.getOrDefault("text", "") + chunk);
                            text.append(chunk);
                            observer.onText(chunk);
                        }
                    }
                    case "input_json_delta" -> toolInputBuffers
                            .computeIfAbsent(index, ignored -> new StringBuilder())
                            .append(delta.path("partial_json").asText(""));
                    case "thinking_delta" -> {
                        String chunk = delta.path("thinking").asText("");
                        block.put("thinking", block.getOrDefault("thinking", "") + chunk);
                        if (!chunk.isEmpty()) {
                            observer.onReasoningDelta(chunk);
                        }
                    }
                    case "signature_delta" -> block.put("signature", delta.path("signature").asText(""));
                    default -> {
                    }
                }
                return false;
            }
            if ("content_block_stop".equals(type)) {
                int index = json.path("index").asInt();
                Map<String, Object> block = contentBlocks.get(index);
                if (block == null || !"tool_use".equals(String.valueOf(block.get("type")))) {
                    return false;
                }
                String rawInput = Optional.ofNullable(toolInputBuffers.get(index)).map(StringBuilder::toString).orElse("{}");
                JsonNode input = parseToolInput(rawInput);
                block.put("input", JSON.convertValue(input, Object.class));
                toolCalls.add(new ToolCall(
                        String.valueOf(block.getOrDefault("id", "")),
                        String.valueOf(block.getOrDefault("name", "")),
                        input
                ));
                return false;
            }
            return false;
        });

        return new StreamingAnthropicTurn(text.toString(), new ArrayList<>(contentBlocks.values()), List.copyOf(toolCalls));
    }

    private String openAiStreamingToolLoop(
            List<ChatMessage> messages,
            String model,
            String apiKey,
            String baseUrl,
            SkillRegistry skills,
            AgentSession agentSession,
            boolean allowDestructiveConfirmation,
            StreamingObserver observer
    ) throws IOException {
        List<Map<String, Object>> payloadMessages = new ArrayList<>(openAiMessagePayload(messages, systemPrompt(skills, agentSession)));
        ToolSession toolSession = new ToolSession(allowDestructiveConfirmation, agentSession);
        StringBuilder fullResponse = new StringBuilder();
        for (int turn = 0; turn < MAX_TOOL_TURNS; turn++) {
            if (turn > 0 && !payloadMessages.isEmpty() && "system".equals(payloadMessages.getFirst().get("role"))) {
                payloadMessages.set(0, Map.of("role", "system", "content", systemPrompt(skills, agentSession)));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", payloadMessages);
            body.put("stream", true);
            body.put("tools", openAiToolDefinitions());

            StreamingOpenAiTurn streamingTurn = openAiStreamingToolTurn(
                    apiUrl(baseUrl, "/v1/chat/completions"),
                    body,
                    Map.of("Authorization", "Bearer " + apiKey),
                    observer
            );
            fullResponse.append(streamingTurn.text());
            if (streamingTurn.toolCalls().isEmpty()) {
                return fullResponse.toString();
            }

            payloadMessages.add(streamingTurn.assistantMessage());
            for (ToolCall toolCall : streamingTurn.toolCalls()) {
                observer.onToolStart(toolCall);
                String result = toolSession.execute(toolCall.name(), toolCall.input());
                observer.onToolEnd(toolCall, result);
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCall.id());
                toolMessage.put("content", result);
                payloadMessages.add(toolMessage);
            }
        }
        throw new CliException("Tool loop exceeded maximum turns (" + MAX_TOOL_TURNS + ")");
    }

    private StreamingOpenAiTurn openAiStreamingToolTurn(
            String url,
            Map<String, Object> body,
            Map<String, String> headers,
            StreamingObserver observer
    ) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        Map<Integer, OpenAiToolCallBuilder> toolBuilders = new TreeMap<>();

        sendSseJsonEvents(url, body, headers, json -> {
            JsonNode choice = json.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            JsonNode reasoningContentDelta = delta.path("reasoning_content");
            if (reasoningContentDelta.isTextual()) {
                String chunk = reasoningContentDelta.asText();
                reasoningContent.append(chunk);
                if (!chunk.isEmpty()) {
                    observer.onReasoningDelta(chunk);
                }
            }
            JsonNode content = delta.path("content");
            if (content.isTextual()) {
                String chunk = content.asText();
                if (!chunk.isEmpty()) {
                    text.append(chunk);
                    observer.onText(chunk);
                }
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    int index = toolCall.path("index").asInt(toolBuilders.size());
                    OpenAiToolCallBuilder builder = toolBuilders.computeIfAbsent(index, ignored -> new OpenAiToolCallBuilder());
                    if (toolCall.path("id").isTextual()) {
                        builder.id = toolCall.path("id").asText();
                    }
                    JsonNode function = toolCall.path("function");
                    if (function.path("name").isTextual()) {
                        builder.name = function.path("name").asText();
                    }
                    if (function.path("arguments").isTextual()) {
                        builder.arguments.append(function.path("arguments").asText());
                    }
                }
            }
            return false;
        });

        List<Map<String, Object>> assistantToolCalls = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<Integer, OpenAiToolCallBuilder> entry : toolBuilders.entrySet()) {
            OpenAiToolCallBuilder builder = entry.getValue();
            String id = firstNonBlank(builder.id, "call_" + UUID.randomUUID());
            String name = firstNonBlank(builder.name, "");
            String rawArguments = builder.arguments.toString();
            JsonNode input = parseToolInput(rawArguments);
            assistantToolCalls.add(Map.of(
                    "id", id,
                    "type", "function",
                    "function", Map.of(
                            "name", name,
                            "arguments", rawArguments.isBlank() ? "{}" : rawArguments
                    )
            ));
            toolCalls.add(new ToolCall(id, name, input));
        }

        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", text.isEmpty() ? null : text.toString());
        if (!reasoningContent.isEmpty()) {
            assistantMessage.put("reasoning_content", reasoningContent.toString());
        }
        if (!assistantToolCalls.isEmpty()) {
            assistantMessage.put("tool_calls", assistantToolCalls);
        }
        return new StreamingOpenAiTurn(text.toString(), assistantMessage, List.copyOf(toolCalls));
    }

    private List<Map<String, Object>> anthropicToolDefinitions() {
        return List.of(
                Map.of(
                        "name", "Read",
                        "description", """
                                读取本地文本文件。file_path 可以是绝对路径，也可以是相对当前工作目录的相对路径。默认从开头最多读取 2000 行，结果会带行号。
                                """,
                        "input_schema", objectSchema(Map.of(
                                "file_path", schemaProperty("string", "要读取的文件路径"),
                                "offset", schemaProperty("integer", "从第几行开始读取，1 为第一行"),
                                "limit", schemaProperty("integer", "最多读取多少行")
                        ), List.of("file_path"))
                ),
                Map.of(
                        "name", "Bash",
                        "description", """
                                在当前工作目录执行非交互 shell 命令，并返回 exit_code、stdout 和 stderr。适合编译、测试、查看命令结果或排查启动报错。不要执行需要交互输入的命令；长期运行的服务应后台启动并把日志写入文件，或设置 timeout_seconds 只捕获启动输出。默认超时 60 秒，最长 600 秒。
                                """,
                        "input_schema", objectSchema(Map.of(
                                "command", schemaProperty("string", "要执行的 shell 命令"),
                                "timeout_seconds", schemaProperty("integer", "超时秒数，默认 60，最大 600")
                        ), List.of("command"))
                ),
                Map.of(
                        "name", "Write",
                        "description", """
                                创建或完整覆盖本地文本文件。仅在创建新文件或完整重写时使用。覆盖已有文件前必须先读取。默认只能写入当前工作目录树内。
                                """,
                        "input_schema", objectSchema(Map.of(
                                "file_path", schemaProperty("string", "要写入的文件路径"),
                                "content", schemaProperty("string", "写入文件的完整内容")
                        ), List.of("file_path", "content"))
                ),
                Map.of(
                        "name", "Edit",
                        "description", """
                                在文本文件中执行精确字符串替换。编辑前必须先读取文件。使用 replace_all 替换所有匹配项。默认只能修改当前工作目录树内文件。
                                """,
                        "input_schema", objectSchema(Map.of(
                                "file_path", schemaProperty("string", "要修改的文件路径"),
                                "old_string", schemaProperty("string", "要替换的精确文本"),
                                "new_string", schemaProperty("string", "替换后的文本"),
                                "replace_all", schemaProperty("boolean", "是否替换所有匹配项，而不是要求唯一匹配")
                        ), List.of("file_path", "old_string", "new_string"))
                ),
                Map.of(
                        "name", "Delete",
                        "description", """
                                删除本地文件或目录。这是破坏性操作，jclaude 会在真正删除前请求用户显式确认。默认只能删除当前工作目录树内路径。删除非空目录时必须设置 recursive=true。
                                """,
                        "input_schema", objectSchema(Map.of(
                                "file_path", schemaProperty("string", "要删除的文件或目录路径"),
                                "recursive", schemaProperty("boolean", "是否递归删除目录及其全部内容；删除普通文件时可省略")
                        ), List.of("file_path"))
                ),
                Map.of(
                        "name", "EnterPlanMode",
                        "description", """
                                进入 Plan Mode。用于复杂或高风险任务的只读探索和实施计划制定。进入后 Bash/Write/Edit/Delete 会被拒绝，直到 ExitPlanMode 的计划获得用户批准。
                                """,
                        "input_schema", objectSchema(Map.of(), List.of())
                ),
                Map.of(
                        "name", "ExitPlanMode",
                        "description", """
                                当你在 Plan Mode 中完成计划后调用。plan 必须包含清晰、可执行的实施方案；jclaude 会把计划展示给用户审批，批准后才允许修改文件。
                                """,
                        "input_schema", objectSchema(Map.of(
                                "plan", schemaProperty("string", "要提交给用户审批的实施计划")
                        ), List.of("plan"))
                )
        );
    }

    private List<Map<String, Object>> openAiToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> tool : anthropicToolDefinitions()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.get("name"));
            function.put("description", tool.get("description"));
            function.put("parameters", tool.get("input_schema"));
            tools.add(Map.of("type", "function", "function", function));
        }
        return tools;
    }

    private static Map<String, Object> schemaProperty(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private boolean hasImages(List<ChatMessage> messages) {
        return messages.stream().anyMatch(ChatMessage::hasImages);
    }

    private List<Map<String, Object>> anthropicMessagePayload(List<ChatMessage> messages) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ChatMessage message : messages) {
            payload.add(Map.of("role", message.role(), "content", anthropicContent(message)));
        }
        return payload;
    }

    private Object anthropicContent(ChatMessage message) {
        if (!message.hasImages()) {
            return message.content();
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (!message.content().isBlank()) {
            content.add(Map.of("type", "text", "text", message.content()));
        }
        for (ImageAttachment image : message.images()) {
            content.add(Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", image.mediaType(),
                            "data", image.base64()
                    )
            ));
        }
        return content;
    }

    private List<Map<String, Object>> openAiMessagePayload(List<ChatMessage> messages, String systemPrompt) throws IOException {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (!systemPrompt.isBlank()) {
            payload.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (ChatMessage message : messages) {
            payload.add(Map.of("role", message.role(), "content", openAiContent(message)));
        }
        return payload;
    }

    private Object openAiContent(ChatMessage message) throws IOException {
        if (!message.hasImages()) {
            return message.content();
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (!message.content().isBlank()) {
            content.add(Map.of("type", "text", "text", message.content()));
        }
        for (ImageAttachment image : message.images()) {
            ImageAttachment compatibleImage = image.openAiCompatible();
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:" + compatibleImage.mediaType() + ";base64," + compatibleImage.base64())
            ));
        }
        return content;
    }

    private void sendSseJsonEvents(
            String url,
            Map<String, Object> body,
            Map<String, String> headers,
            StreamingEventHandler handler
    ) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpResponse<InputStream> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new CliException("Provider API returned HTTP " + response.statusCode() + ": " + responseBody);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                StringBuilder eventData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (processSseJsonEvent(eventData.toString(), handler)) {
                            break;
                        }
                        eventData.setLength(0);
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        eventData.append(line.substring(5).trim()).append('\n');
                    }
                }
                if (!eventData.isEmpty()) {
                    processSseJsonEvent(eventData.toString(), handler);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CliException("Request interrupted");
        } catch (JsonProcessingException exception) {
            throw new CliException("Invalid streaming JSON from provider: " + exception.getMessage());
        }
    }

    private boolean processSseJsonEvent(String rawData, StreamingEventHandler handler) throws IOException {
        String data = rawData.trim();
        if (data.isBlank()) {
            return false;
        }
        if ("[DONE]".equals(data)) {
            return true;
        }
        return handler.handle(JSON.readTree(data));
    }

    private interface StreamingObserver {
        void onText(String chunk);

        default void onReasoningDelta(String chunk) {
        }

        default void onToolStart(ToolCall toolCall) {
        }

        default void onToolEnd(ToolCall toolCall, String result) {
        }

        static StreamingObserver forText(Consumer<String> onText) {
            return new StreamingObserver() {
                @Override
                public void onText(String chunk) {
                    onText.accept(chunk);
                }
            };
        }
    }

    private interface StreamingEventHandler {
        boolean handle(JsonNode json) throws IOException;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(JSON.convertValue(node, Map.class));
    }

    private JsonNode parseToolInput(String rawInput) {
        try {
            String value = rawInput == null || rawInput.isBlank() ? "{}" : rawInput;
            return JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            return JSON.createObjectNode().put("jclaude_argument_error", exception.getMessage());
        }
    }

    private String envValue(String name) {
        return name == null || name.isBlank() ? null : System.getenv(name);
    }

    private String apiKey(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> firstNonBlank(System.getenv("ANTHROPIC_API_KEY"), System.getenv("JCLAUDE_API_KEY"));
            case OPENAI -> firstNonBlank(System.getenv("OPENAI_API_KEY"), System.getenv("JCLAUDE_API_KEY"));
        };
    }

    private String baseUrl(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> firstNonBlank(System.getenv("JCLAUDE_BASE_URL"), System.getenv("ANTHROPIC_BASE_URL"), "https://api.anthropic.com");
            case OPENAI -> firstNonBlank(System.getenv("JCLAUDE_BASE_URL"), System.getenv("OPENAI_BASE_URL"), "https://api.openai.com");
        };
    }

    private String apiUrl(String baseUrl, String defaultPath) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (normalized.endsWith("/messages") || normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + defaultPath.substring(3);
        }
        return normalized + defaultPath;
    }

    private String localResponse(ChatMessage message, Provider provider, SkillRegistry skills) {
        String imageLine = message.hasImages()
                ? System.lineSeparator() + "已附加图片：" + message.images().size() + " 张。"
                : "";
        return "本地 jclaude 原型已收到：" + message.content() + imageLine + System.lineSeparator()
                + "设置 " + provider.primaryKeyEnv() + " 后可连接 provider：" + provider.id() + "。"
                + System.lineSeparator()
                + "已加载 skills：" + skills.size() + "。可用 /skills 查看，或用 /<skill-name> 调用。";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String readStdin() throws IOException {
        Console console = System.console();
        if (console != null) {
            return "";
        }
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private void printHelp() {
        System.out.println("""
                Usage: jclaude [options] [prompt]
                       jclaude <command> [args]

                Java 21 CLI inspired by Claude Code. The executable name is jclaude.

                Options:
                  -p, --print                   Print response and exit
                      --output-format <format>  text, json, or stream-json
                      --input-format <format>   text or stream-json placeholder
                      --model <model>           Model name to use
                      --provider <provider>     anthropic or openai
                      --base-url <url>          Override provider base URL or full endpoint
                      --allowed-tools <tools>   Comma-separated allowlist placeholder
                      --disallowed-tools <tools> Comma-separated denylist placeholder
                      --mcp-config <path>       MCP config path placeholder
                      --permission-mode <mode>  Permission mode placeholder
                  -c, --continue                Continue last conversation placeholder
                  -r, --resume [id]             Resume conversation placeholder
                      --settings <path>         Settings file path placeholder
                      --add-dir <path>          Additional directory to scan for .claude/.jclaude skills
                      --agents <path>           Agents directory placeholder
                  -v, --version                 Show version
                  -h, --help                    Show help

                Commands:
                  config paths   Show config locations
                  doctor         Show environment diagnostics
                  auth/login/logout, mcp, plugin, completion, install, update

                Interactive slash commands:
                  /help, /status, /config, /model, /skills, /plan, /clear, /reset, /exit

                Interactive autocomplete:
                  Type / to list commands and skills; type @ to list files and directories.
                  Use ↑/↓ to select and Tab to accept. Selecting a directory keeps browsing inside it.
                  Use ←/→ to edit in the middle; when autocomplete is closed, ↑/↓ recalls input history.

                Skills:
                  Put skills in .jclaude/skills/<name>/SKILL.md or .claude/skills/<name>/SKILL.md.
                  Invoke user skills with /<skill-name> [args].
                """);
    }

    private String toJson(Map<String, String> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escapeJson(entry.getKey())).append("\":");
            builder.append('"').append(escapeJson(entry.getValue())).append('"');
        }
        return builder.append('}').toString();
    }

    private String escapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String unquote(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static Path resolveLocalPath(String rawPath) {
        String value = unquote(rawPath == null ? "" : rawPath.trim());
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~" + java.io.File.separator)) {
            value = System.getProperty("user.home") + value.substring(1);
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(path);
        }
        return path.normalize();
    }

    private static boolean isWritablePath(Path path) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        String allowOutsideCwd = System.getenv("JCLAUDE_ALLOW_WRITE_OUTSIDE_CWD");
        return "true".equalsIgnoreCase(allowOutsideCwd) || "1".equals(allowOutsideCwd) || absolute.startsWith(cwd);
    }

    private static String displayPath(Path path) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(cwd)) {
            Path relative = cwd.relativize(absolute);
            return relative.toString().isBlank() ? "." : relative.toString();
        }
        return absolute.toString();
    }

    private static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String[] parts = content.split("\n", -1);
        int length = parts.length;
        if (content.endsWith("\n")) {
            length--;
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < length; index++) {
            lines.add(parts[index]);
        }
        return lines;
    }

    private static String formatNumberedLines(List<String> lines, int startLine) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            builder.append(String.format("%6d\t%s%n", startLine + index, lines.get(index)));
        }
        return builder.toString();
    }

    private static int optionalPositiveInt(JsonNode input, String field, int defaultValue) {
        JsonNode value = input.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        int parsed = value.asInt(defaultValue);
        if (parsed < 1) {
            throw new CliException(field + " must be >= 1");
        }
        return parsed;
    }

    private static boolean optionalBoolean(JsonNode input, String field, boolean defaultValue) {
        JsonNode value = input.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return switch (value.asText("").trim().toLowerCase()) {
            case "true", "yes", "y", "1", "on" -> true;
            case "false", "no", "n", "0", "off" -> false;
            default -> defaultValue;
        };
    }

    private static String requiredText(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (!value.isTextual()) {
            throw new CliException("Missing required string field: " + field);
        }
        return value.asText();
    }

    private static String toolError(String message) {
        return "<tool_use_error>" + escapeXml(message == null || message.isBlank() ? "Unknown error" : message) + "</tool_use_error>";
    }

    private static String stripAtMentionTrailingPunctuation(String value) {
        String result = value;
        while (!result.isBlank()) {
            char last = result.charAt(result.length() - 1);
            if (",.;:!?，。；：！？".indexOf(last) < 0) {
                break;
            }
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isImagePath(Path path) {
        String filename = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        return filename.endsWith(".jpg")
                || filename.endsWith(".jpeg")
                || filename.endsWith(".png")
                || filename.endsWith(".gif")
                || filename.endsWith(".webp");
    }

    private static List<String> shellCommand(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of(firstNonBlank(System.getenv("SHELL"), "/bin/sh"), "-lc", command);
    }

    private static void terminateProcess(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static void finishOutputCollector(ProcessOutputCollector collector) throws InterruptedException {
        if (!collector.awaitCompletion(1_000)) {
            collector.close();
            collector.awaitCompletion(1_000);
        }
    }

    private static String formatCommandResult(
            String command,
            Path cwd,
            int timeoutSeconds,
            boolean timedOut,
            int exitCode,
            CapturedOutput stdout,
            CapturedOutput stderr
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("<command_result>").append(System.lineSeparator());
        builder.append("<command>").append(escapeXml(command)).append("</command>").append(System.lineSeparator());
        builder.append("<cwd>").append(escapeXml(cwd.toString())).append("</cwd>").append(System.lineSeparator());
        builder.append("<timeout_seconds>").append(timeoutSeconds).append("</timeout_seconds>").append(System.lineSeparator());
        builder.append("<timed_out>").append(timedOut).append("</timed_out>").append(System.lineSeparator());
        builder.append("<exit_code>").append(exitCode).append("</exit_code>").append(System.lineSeparator());
        appendCapturedOutput(builder, "stdout", stdout);
        appendCapturedOutput(builder, "stderr", stderr);
        builder.append("</command_result>");
        return builder.toString();
    }

    private static void appendCapturedOutput(StringBuilder builder, String name, CapturedOutput output) {
        builder.append("<")
                .append(name)
                .append(" truncated=\"")
                .append(output.truncated())
                .append("\" bytes=\"")
                .append(output.bytesRead())
                .append("\">")
                .append(System.lineSeparator())
                .append(escapeXml(output.text()))
                .append(System.lineSeparator())
                .append("</")
                .append(name)
                .append(">")
                .append(System.lineSeparator());
    }

    private static String toolStartSummary(ToolCall toolCall) {
        JsonNode input = toolCall.input();
        return switch (toolCall.name()) {
            case "Bash" -> "Bash: " + abbreviate(oneLine(input.path("command").asText("")), 140);
            case "Read" -> "Read: " + abbreviate(input.path("file_path").asText(""), 120);
            case "Write" -> "Write: " + abbreviate(input.path("file_path").asText(""), 120);
            case "Edit" -> "Edit: " + abbreviate(input.path("file_path").asText(""), 120);
            case "Delete" -> "Delete: " + abbreviate(input.path("file_path").asText(""), 120);
            case "EnterPlanMode" -> "EnterPlanMode";
            case "ExitPlanMode" -> "ExitPlanMode";
            default -> toolCall.name().isBlank() ? "Tool" : toolCall.name();
        };
    }

    private static String toolEndSummary(ToolCall toolCall, String result) {
        boolean error = result.contains("<tool_use_error>");
        if ("Bash".equals(toolCall.name())) {
            String exitCode = tagValue(result, "exit_code").orElse(error ? "error" : "?");
            return (error || !"0".equals(exitCode) ? "✗ " : "✓ ") + "Bash exit " + exitCode;
        }
        return (error ? "✗ " : "✓ ") + (toolCall.name().isBlank() ? "Tool" : toolCall.name());
    }

    private static Optional<String> tagValue(String value, String tagName) {
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";
        int start = value.indexOf(startTag);
        if (start < 0) {
            return Optional.empty();
        }
        int valueStart = start + startTag.length();
        int end = value.indexOf(endTag, valueStart);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(value.substring(valueStart, end).trim());
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String abbreviate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 1) {
            return "…";
        }
        return value.substring(0, maxChars - 1) + "…";
    }

    private record ToolCall(String id, String name, JsonNode input) {
    }

    private record StreamingAnthropicTurn(String text, List<Map<String, Object>> contentBlocks, List<ToolCall> toolCalls) {
    }

    private record StreamingOpenAiTurn(String text, Map<String, Object> assistantMessage, List<ToolCall> toolCalls) {
    }

    private static final class OpenAiToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    private record ReadState(String content, long timestamp, boolean partialView) {
    }

    private record CapturedOutput(String text, boolean truncated, long bytesRead) {
    }

    private static final class InteractiveStreamingObserver implements StreamingObserver {
        private boolean lineStart = true;
        private boolean statusActive;
        private boolean sawOutput;

        @Override
        public void onText(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            clearStatus();
            System.out.print(chunk);
            System.out.flush();
            sawOutput = true;
            lineStart = chunk.endsWith("\n") || chunk.endsWith("\r");
        }

        @Override
        public void onReasoningDelta(String chunk) {
            if (chunk == null || chunk.isEmpty() || statusActive) {
                return;
            }
            if (!lineStart) {
                System.out.println();
            }
            System.out.print("思考中...");
            System.out.flush();
            statusActive = true;
            sawOutput = true;
            lineStart = false;
        }

        @Override
        public void onToolStart(ToolCall toolCall) {
            clearStatus();
            if (!lineStart) {
                System.out.println();
            }
            System.out.println("→ " + toolStartSummary(toolCall));
            System.out.flush();
            sawOutput = true;
            lineStart = true;
        }

        @Override
        public void onToolEnd(ToolCall toolCall, String result) {
            clearStatus();
            System.out.println(toolEndSummary(toolCall, result));
            System.out.flush();
            sawOutput = true;
            lineStart = true;
        }

        void finish() {
            clearStatus();
            if (!lineStart || !sawOutput) {
                System.out.println();
                System.out.flush();
                lineStart = true;
            }
        }

        private void clearStatus() {
            if (!statusActive) {
                return;
            }
            System.out.print("\r\033[2K");
            System.out.flush();
            statusActive = false;
            lineStart = true;
        }
    }

    private static final class ProcessOutputCollector {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;
        private long bytesRead;
        private boolean truncated;

        ProcessOutputCollector(InputStream stream, String name) {
            this.stream = stream;
            this.thread = new Thread(this::readLoop, "jclaude-" + name + "-collector");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        boolean awaitCompletion(long millis) throws InterruptedException {
            thread.join(millis);
            return !thread.isAlive();
        }

        void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }

        synchronized CapturedOutput snapshot() {
            return new CapturedOutput(buffer.toString(StandardCharsets.UTF_8), truncated, bytesRead);
        }

        private void readLoop() {
            byte[] chunk = new byte[8192];
            try (InputStream input = stream) {
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    append(chunk, read);
                }
            } catch (IOException ignored) {
            }
        }

        private synchronized void append(byte[] chunk, int read) {
            bytesRead += read;
            int remaining = MAX_COMMAND_OUTPUT_BYTES - buffer.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int bytesToKeep = Math.min(read, remaining);
            buffer.write(chunk, 0, bytesToKeep);
            if (bytesToKeep < read) {
                truncated = true;
            }
        }
    }

    private static final class AgentSession {
        private PermissionMode permissionMode = PermissionMode.DEFAULT;
        private String lastPlan;

        boolean isPlanMode() {
            return permissionMode == PermissionMode.PLAN;
        }

        String modeLabel() {
            return permissionMode.label();
        }

        void enterPlanMode() {
            permissionMode = PermissionMode.PLAN;
        }

        void exitPlanMode() {
            permissionMode = PermissionMode.DEFAULT;
        }

        void rememberPlan(String plan) {
            lastPlan = plan;
        }

        Optional<String> lastPlan() {
            return Optional.ofNullable(lastPlan);
        }
    }

    private enum PermissionMode {
        DEFAULT("default"),
        PLAN("plan");

        private final String label;

        PermissionMode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private record AtFileReference(String original, Path path, Optional<Integer> lineStart, Optional<Integer> lineEnd) {
        static AtFileReference parse(String raw) {
            String mention = stripAtMentionTrailingPunctuation(raw);
            Matcher matcher = AT_FILE_LINE_RANGE_PATTERN.matcher(mention);
            if (!matcher.matches()) {
                return new AtFileReference(mention, resolveLocalPath(mention), Optional.empty(), Optional.empty());
            }
            String filename = matcher.group(1) == null ? mention : matcher.group(1);
            Optional<Integer> lineStart = matcher.group(2) == null ? Optional.empty() : Optional.of(Integer.parseInt(matcher.group(2)));
            Optional<Integer> lineEnd = matcher.group(3) == null ? lineStart : Optional.of(Integer.parseInt(matcher.group(3)));
            return new AtFileReference(mention, resolveLocalPath(filename), lineStart, lineEnd);
        }
    }

    private static final class ToolSession {
        private final Map<Path, ReadState> readFileState = new LinkedHashMap<>();
        private final boolean allowDestructiveConfirmation;
        private final AgentSession agentSession;

        ToolSession(boolean allowDestructiveConfirmation, AgentSession agentSession) {
            this.allowDestructiveConfirmation = allowDestructiveConfirmation;
            this.agentSession = agentSession;
        }

        String execute(String name, JsonNode input) {
            try {
                if (input.has("jclaude_argument_error")) {
                    return toolError("工具参数不是有效 JSON：" + input.path("jclaude_argument_error").asText());
                }
                return switch (name) {
                    case "Read" -> read(input);
                    case "Bash" -> {
                        requireNotPlanMode("Bash");
                        yield bash(input);
                    }
                    case "Write" -> {
                        requireNotPlanMode("Write");
                        yield write(input);
                    }
                    case "Edit" -> {
                        requireNotPlanMode("Edit");
                        yield edit(input);
                    }
                    case "Delete" -> {
                        requireNotPlanMode("Delete");
                        yield delete(input);
                    }
                    case "EnterPlanMode" -> enterPlanMode();
                    case "ExitPlanMode" -> exitPlanMode(input);
                    default -> toolError("未知工具：" + name);
                };
            } catch (IOException | RuntimeException exception) {
                return toolError(exception.getMessage());
            }
        }

        private String enterPlanMode() {
            agentSession.enterPlanMode();
            return """
                    已进入 Plan Mode。
                    现在应只进行读取、分析和计划制定；Bash/Write/Edit/Delete 会被拒绝。
                    完成计划后调用 ExitPlanMode，并在 plan 参数中提交计划等待用户批准。
                    """;
        }

        private String exitPlanMode(JsonNode input) throws IOException {
            if (!agentSession.isPlanMode()) {
                throw new CliException("当前不在 Plan Mode。无需调用 ExitPlanMode；如果计划已经获批，请继续执行。");
            }
            String plan = requiredText(input, "plan").trim();
            if (plan.isBlank()) {
                throw new CliException("ExitPlanMode 需要非空 plan。");
            }
            agentSession.rememberPlan(plan);
            if (!confirmPlan(plan)) {
                return """
                        用户未批准该计划。仍处于 Plan Mode。
                        请根据用户反馈继续读取、分析或修订计划，不要写入、编辑或删除文件。
                        """;
            }
            agentSession.exitPlanMode();
            return """
                    用户已批准计划，已退出 Plan Mode。
                    现在可以按获批计划执行；继续遵守写入前先读取、破坏性操作需确认等安全规则。
                    """;
        }

        private void requireNotPlanMode(String toolName) {
            if (agentSession.isPlanMode()) {
                throw new CliException(toolName + " 在 Plan Mode 中被禁止。请先完成计划并调用 ExitPlanMode，待用户批准后再修改文件。");
            }
        }

        private String read(JsonNode input) throws IOException {
            Path path = resolveLocalPath(requiredText(input, "file_path"));
            int offset = optionalPositiveInt(input, "offset", 1);
            int limit = optionalPositiveInt(input, "limit", MAX_TOOL_READ_LINES);

            if (!Files.exists(path)) {
                throw new CliException("文件不存在：" + path);
            }
            if (Files.isDirectory(path)) {
                return listDirectory(path);
            }
            if (!Files.isRegularFile(path)) {
                throw new CliException("路径不是普通文件：" + path);
            }
            long size = Files.size(path);
            if (size > MAX_TOOL_TEXT_FILE_BYTES) {
                throw new CliException("文件过大，无法作为文本读取（" + size + " 字节）：" + path);
            }
            String content = normalizeLineEndings(Files.readString(path, StandardCharsets.UTF_8));
            if (content.indexOf('\0') >= 0) {
                throw new CliException("文件看起来是二进制文件：" + path);
            }
            List<String> allLines = splitLines(content);
            if (allLines.isEmpty()) {
                readFileState.put(path, new ReadState(content, Files.getLastModifiedTime(path).toMillis(), false));
                return "<system-reminder>提醒：文件存在，但内容为空。</system-reminder>";
            }
            int fromIndex = Math.min(offset - 1, allLines.size());
            int toIndex = Math.min(allLines.size(), fromIndex + limit);
            List<String> selected = new ArrayList<>(allLines.subList(fromIndex, toIndex));
            boolean partial = offset != 1 || toIndex < allLines.size();
            readFileState.put(path, new ReadState(content, Files.getLastModifiedTime(path).toMillis(), partial));

            StringBuilder result = new StringBuilder();
            if (selected.isEmpty()) {
                result.append("<system-reminder>提醒：文件存在，但长度短于提供的 offset（")
                        .append(offset)
                        .append("）。文件总行数为 ")
                        .append(allLines.size())
                        .append(" 行。</system-reminder>");
            } else {
                result.append(formatNumberedLines(selected, offset));
                if (partial) {
                    result.append("<system-reminder>文件内容仅展示部分：当前展示第 ")
                            .append(offset)
                            .append("-")
                            .append(toIndex)
                            .append(" 行，共 ")
                            .append(allLines.size())
                            .append(" 行。</system-reminder>");
                }
            }
            return result.toString();
        }

        private String bash(JsonNode input) throws IOException {
            String command = requiredText(input, "command").trim();
            if (command.isBlank()) {
                throw new CliException("command 不能为空。");
            }
            int timeoutSeconds = optionalPositiveInt(input, "timeout_seconds", DEFAULT_COMMAND_TIMEOUT_SECONDS);
            if (timeoutSeconds > MAX_COMMAND_TIMEOUT_SECONDS) {
                throw new CliException("timeout_seconds 不能超过 " + MAX_COMMAND_TIMEOUT_SECONDS + "。");
            }

            Path cwd = Path.of("").toAbsolutePath().normalize();
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(cwd.toFile());
            builder.environment().putIfAbsent("NO_COLOR", "1");
            Process process = builder.start();
            ProcessOutputCollector stdout = new ProcessOutputCollector(process.getInputStream(), "stdout");
            ProcessOutputCollector stderr = new ProcessOutputCollector(process.getErrorStream(), "stderr");

            boolean timedOut = false;
            int exitCode;
            try {
                if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    exitCode = process.exitValue();
                } else {
                    timedOut = true;
                    terminateProcess(process);
                    exitCode = 124;
                }
                finishOutputCollector(stdout);
                finishOutputCollector(stderr);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                stdout.close();
                stderr.close();
                throw new CliException("命令执行被中断：" + command);
            }

            return formatCommandResult(
                    command,
                    cwd,
                    timeoutSeconds,
                    timedOut,
                    exitCode,
                    stdout.snapshot(),
                    stderr.snapshot()
            );
        }

        private String write(JsonNode input) throws IOException {
            Path path = resolveLocalPath(requiredText(input, "file_path"));
            String content = requiredText(input, "content");
            requireWritable(path);
            boolean existed = Files.exists(path);
            if (existed) {
                requireFreshFullRead(path);
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            readFileState.put(path, new ReadState(
                    normalizeLineEndings(content),
                    Files.getLastModifiedTime(path).toMillis(),
                    false
            ));
            return existed
                    ? "文件已成功更新：" + path
                    : "文件已成功创建：" + path;
        }

        private String edit(JsonNode input) throws IOException {
            Path path = resolveLocalPath(requiredText(input, "file_path"));
            String oldString = requiredText(input, "old_string");
            String newString = requiredText(input, "new_string");
            boolean replaceAll = optionalBoolean(input, "replace_all", false);
            requireWritable(path);
            if (oldString.equals(newString)) {
                throw new CliException("无需修改：old_string 和 new_string 完全相同。");
            }
            if (!Files.exists(path)) {
                if (oldString.isEmpty()) {
                    return createViaEdit(path, newString);
                }
                throw new CliException("文件不存在：" + path);
            }
            requireFreshFullRead(path);
            String content = normalizeLineEndings(Files.readString(path, StandardCharsets.UTF_8));
            String updated;
            int replacements;
            if (oldString.isEmpty()) {
                if (!content.isEmpty()) {
                    throw new CliException("无法创建新文件：文件已存在。");
                }
                updated = newString;
                replacements = 1;
            } else {
                replacements = countOccurrences(content, oldString);
                if (replacements == 0) {
                    throw new CliException("文件中未找到要替换的字符串。\n字符串：" + oldString);
                }
                if (replacements > 1 && !replaceAll) {
                    throw new CliException("找到 " + replacements + " 处匹配，但 replace_all 为 false。");
                }
                updated = replaceAll
                        ? content.replace(oldString, newString)
                        : replaceFirstLiteral(content, oldString, newString);
            }
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            readFileState.put(path, new ReadState(updated, Files.getLastModifiedTime(path).toMillis(), false));
            return "文件已成功编辑：" + path + "。替换次数：" + replacements + "。";
        }

        private String delete(JsonNode input) throws IOException {
            Path path = resolveLocalPath(requiredText(input, "file_path"));
            boolean recursive = optionalBoolean(input, "recursive", false);
            requireWritable(path, "Delete");
            if (!Files.exists(path)) {
                throw new CliException("文件不存在，未删除：" + path);
            }
            rejectDangerousDeleteTarget(path);
            if (Files.isDirectory(path) && !recursive && !isDirectoryEmpty(path)) {
                throw new CliException("目录非空，未删除。若确实要删除整个目录，请设置 recursive=true：" + path);
            }
            if (!confirmDelete(path, recursive)) {
                throw new CliException("用户未确认删除，操作未执行：" + path);
            }
            int deletedCount = deletePath(path, recursive);
            removeReadState(path);
            return "删除成功：" + path + "。删除条目数：" + deletedCount + "。";
        }

        private String createViaEdit(Path path, String content) throws IOException {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            readFileState.put(path, new ReadState(
                    normalizeLineEndings(content),
                    Files.getLastModifiedTime(path).toMillis(),
                    false
            ));
            return "文件已成功创建：" + path;
        }

        private void requireWritable(Path path) {
            requireWritable(path, "Write/Edit");
        }

        private void requireWritable(Path path, String operation) {
            if (!isWritablePath(path)) {
                throw new CliException(operation + " 默认限制在当前工作目录树内。若确需操作目录外路径，请设置 JCLAUDE_ALLOW_WRITE_OUTSIDE_CWD=true。路径：" + path);
            }
        }

        private void requireFreshFullRead(Path path) throws IOException {
            ReadState state = readFileState.get(path);
            if (state == null || state.partialView()) {
                throw new CliException("尚未完整读取该文件。写入或编辑前请先读取。");
            }
            String current = normalizeLineEndings(Files.readString(path, StandardCharsets.UTF_8));
            if (!current.equals(state.content())) {
                throw new CliException("文件自读取后已被修改。再次写入或编辑前请重新读取。");
            }
        }

        private String listDirectory(Path path) throws IOException {
            List<Path> entries;
            try (Stream<Path> stream = Files.list(path)) {
                entries = stream
                        .sorted((left, right) -> {
                            boolean leftDir = Files.isDirectory(left);
                            boolean rightDir = Files.isDirectory(right);
                            if (leftDir != rightDir) {
                                return leftDir ? -1 : 1;
                            }
                            return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
                        })
                        .limit(1_000)
                        .toList();
            }
            StringBuilder builder = new StringBuilder("目录：").append(path).append(System.lineSeparator());
            for (Path entry : entries) {
                builder.append(Files.isDirectory(entry) ? "[dir]  " : "[file] ")
                        .append(entry.getFileName())
                        .append(System.lineSeparator());
            }
            return builder.toString();
        }

        private boolean isDirectoryEmpty(Path path) throws IOException {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.findAny().isEmpty();
            }
        }

        private void rejectDangerousDeleteTarget(Path path) {
            Path absolute = path.toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            Path root = absolute.getRoot();
            if (root != null && absolute.equals(root)) {
                throw new CliException("拒绝删除文件系统根目录：" + absolute);
            }
            if (absolute.equals(cwd)) {
                throw new CliException("拒绝删除当前工作目录：" + absolute);
            }
            if (absolute.equals(home)) {
                throw new CliException("拒绝删除用户主目录：" + absolute);
            }
        }

        private boolean confirmDelete(Path path, boolean recursive) throws IOException {
            String autoConfirm = System.getenv("JCLAUDE_AUTO_CONFIRM_DESTRUCTIVE");
            if ("true".equalsIgnoreCase(autoConfirm) || "1".equals(autoConfirm)) {
                return true;
            }
            if (!allowDestructiveConfirmation) {
                throw new CliException("删除是破坏性操作，当前非交互模式不会自动执行。请进入交互模式确认，或手动删除该路径：" + path);
            }
            String prompt = "确认删除%s：%s ? 输入 yes 确认："
                    .formatted(recursive ? "（递归）" : "", path);
            Console console = System.console();
            String answer;
            if (console != null) {
                answer = console.readLine(prompt);
            } else {
                System.out.print(prompt);
                System.out.flush();
                answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
            return answer != null && ("yes".equalsIgnoreCase(answer.trim()) || "确认".equals(answer.trim()));
        }

        private boolean confirmPlan(String plan) throws IOException {
            String autoApprove = System.getenv("JCLAUDE_AUTO_APPROVE_PLAN");
            if ("true".equalsIgnoreCase(autoApprove) || "1".equals(autoApprove)) {
                return true;
            }
            if (!allowDestructiveConfirmation) {
                throw new CliException("ExitPlanMode 需要用户审批计划；当前非交互模式不会自动批准。请进入交互模式审批，或设置 JCLAUDE_AUTO_APPROVE_PLAN=true。");
            }
            System.out.println();
            System.out.println("----- jclaude plan -----");
            System.out.println(plan);
            System.out.println("----- end plan -----");
            String prompt = "批准该计划并退出 Plan Mode？输入 yes 或 确认：";
            Console console = System.console();
            String answer;
            if (console != null) {
                answer = console.readLine(prompt);
            } else {
                System.out.print(prompt);
                System.out.flush();
                answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
            return answer != null && ("yes".equalsIgnoreCase(answer.trim()) || "确认".equals(answer.trim()));
        }

        private int deletePath(Path path, boolean recursive) throws IOException {
            if (!recursive || !Files.isDirectory(path)) {
                Files.delete(path);
                return 1;
            }
            List<Path> paths;
            try (Stream<Path> stream = Files.walk(path)) {
                paths = stream
                        .sorted(Comparator.reverseOrder())
                        .toList();
            }
            for (Path current : paths) {
                Files.delete(current);
            }
            return paths.size();
        }

        private void removeReadState(Path path) {
            Path absolute = path.toAbsolutePath().normalize();
            readFileState.keySet().removeIf(readPath -> readPath.toAbsolutePath().normalize().startsWith(absolute));
        }

        private int countOccurrences(String content, String needle) {
            int count = 0;
            int index = 0;
            while ((index = content.indexOf(needle, index)) >= 0) {
                count++;
                index += needle.length();
            }
            return count;
        }

        private String replaceFirstLiteral(String content, String oldString, String newString) {
            int index = content.indexOf(oldString);
            if (index < 0) {
                return content;
            }
            return content.substring(0, index) + newString + content.substring(index + oldString.length());
        }
    }

    private enum Provider {
        ANTHROPIC("anthropic", "ANTHROPIC_API_KEY 或 JCLAUDE_API_KEY"),
        OPENAI("openai", "OPENAI_API_KEY 或 JCLAUDE_API_KEY");

        private final String id;
        private final String primaryKeyEnv;

        Provider(String id, String primaryKeyEnv) {
            this.id = id;
            this.primaryKeyEnv = primaryKeyEnv;
        }

        String id() {
            return id;
        }

        String primaryKeyEnv() {
            return primaryKeyEnv;
        }

        static Provider parse(String value) {
            for (Provider provider : values()) {
                if (provider.id.equals(value)) {
                    return provider;
                }
            }
            throw new CliException("Unsupported provider: " + value);
        }
    }

    private static final class SkillRegistry {
        private final List<Skill> skills;
        private final Map<String, Skill> skillsByName;

        private SkillRegistry(List<Skill> skills) {
            this.skills = List.copyOf(skills);
            Map<String, Skill> index = new LinkedHashMap<>();
            for (Skill skill : skills) {
                index.putIfAbsent(skill.name(), skill);
                index.putIfAbsent(skill.name().toLowerCase(), skill);
            }
            this.skillsByName = Map.copyOf(index);
        }

        static SkillRegistry load(Path configDir, CliRequest request) throws IOException {
            LinkedHashSet<Path> skillDirs = new LinkedHashSet<>();
            addAdditionalSkillDirs(skillDirs, request.option("add-dir"));
            addProjectSkillDirs(skillDirs, Path.of("").toAbsolutePath().normalize());

            Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            addIfDirectory(skillDirs, configDir.resolve("skills"));
            addIfDirectory(skillDirs, home.resolve(".jclaude").resolve("skills"));
            addIfDirectory(skillDirs, home.resolve(".claude").resolve("skills"));

            List<Skill> loaded = new ArrayList<>();
            Set<Path> seenFiles = new HashSet<>();
            Set<String> seenNames = new HashSet<>();
            for (Path skillDir : skillDirs) {
                for (Skill skill : loadSkillsFromDirectory(skillDir)) {
                    Path identity = safeRealPath(skill.filePath());
                    if (identity != null && !seenFiles.add(identity)) {
                        continue;
                    }
                    String normalizedName = skill.name().toLowerCase();
                    if (!seenNames.add(normalizedName)) {
                        continue;
                    }
                    loaded.add(skill);
                }
            }
            return new SkillRegistry(loaded);
        }

        int size() {
            return skills.size();
        }

        boolean isEmpty() {
            return skills.isEmpty();
        }

        String describeForHumans() {
            StringBuilder builder = new StringBuilder("Loaded skills:");
            for (Skill skill : skills) {
                builder.append(System.lineSeparator())
                        .append("- /")
                        .append(skill.name())
                        .append(": ")
                        .append(skill.description());
                skill.whenToUse().ifPresent(value -> builder.append(" — ").append(value));
                skill.argumentHint().ifPresent(value -> builder.append(" ").append(value));
                builder.append(" [").append(skill.filePath()).append("]");
            }
            return builder.toString();
        }

        String systemPrompt() {
            if (skills.isEmpty()) {
                return "";
            }
            return """
                    # Skills
                    Local skills are reusable instructions loaded from SKILL.md files.
                    If the user invoked a skill, the prompt will contain a <jclaude_skill_invocation> block with the full skill body. Follow that skill body directly.
                    Available skills for slash invocation:

                    """
                    + formatSkillListing();
        }

        Optional<SkillInvocation> resolveInvocation(String input) {
            ParsedInvocation parsed = ParsedInvocation.parse(input);
            if (parsed == null) {
                return Optional.empty();
            }
            Skill skill = skillsByName.get(parsed.name());
            if (skill == null) {
                skill = skillsByName.get(parsed.name().toLowerCase());
            }
            if (skill == null) {
                return Optional.empty();
            }
            if (skill.disableModelInvocation()) {
                throw new CliException("Skill cannot be invoked because disable-model-invocation is true: " + skill.name());
            }
            if (!skill.userInvocable()) {
                throw new CliException("Skill can only be invoked by the model, not directly by users: " + skill.name());
            }
            return Optional.of(new SkillInvocation(skill, parsed.args()));
        }

        Optional<String> expandIfSkillInvocation(String input) {
            return resolveInvocation(input).map(SkillInvocation::expandedPrompt);
        }

        Optional<String> skillNameFromInvocation(String input) {
            return resolveInvocation(input).map(invocation -> invocation.skill().name());
        }

        private String formatSkillListing() {
            StringBuilder builder = new StringBuilder();
            for (Skill skill : skills) {
                String description = skill.description();
                if (skill.whenToUse().isPresent()) {
                    description = description + " - " + skill.whenToUse().get();
                }
                if (description.length() > MAX_SKILL_LISTING_DESCRIPTION_CHARS) {
                    description = description.substring(0, MAX_SKILL_LISTING_DESCRIPTION_CHARS - 1) + "…";
                }
                String line = "- " + skill.name() + ": " + description;
                if (builder.length() + line.length() + 1 > SKILL_LISTING_CHAR_BUDGET) {
                    builder.append("- ... (skill listing truncated)");
                    break;
                }
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }

        private static void addAdditionalSkillDirs(Set<Path> dirs, Optional<String> addDirOption) {
            if (addDirOption.isEmpty() || addDirOption.get().isBlank()) {
                return;
            }
            String[] rawDirs = addDirOption.get().split("\\s*(?:,|" + Pattern.quote(System.getProperty("path.separator")) + ")\\s*");
            for (String rawDir : rawDirs) {
                if (rawDir.isBlank()) {
                    continue;
                }
                Path dir = Path.of(rawDir.trim()).toAbsolutePath().normalize();
                addIfDirectory(dirs, dir);
                addIfDirectory(dirs, dir.resolve(".jclaude").resolve("skills"));
                addIfDirectory(dirs, dir.resolve(".claude").resolve("skills"));
            }
        }

        private static void addProjectSkillDirs(Set<Path> dirs, Path cwd) {
            Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            Optional<Path> gitRoot = findGitRoot(cwd);
            Path current = cwd;
            while (current != null) {
                if (samePath(current, home)) {
                    break;
                }
                addIfDirectory(dirs, current.resolve(".jclaude").resolve("skills"));
                addIfDirectory(dirs, current.resolve(".claude").resolve("skills"));
                if (gitRoot.isPresent() && samePath(current, gitRoot.get())) {
                    break;
                }
                Path parent = current.getParent();
                if (parent == null || samePath(parent, current)) {
                    break;
                }
                current = parent;
            }
        }

        private static Optional<Path> findGitRoot(Path cwd) {
            Path current = cwd;
            while (current != null) {
                if (Files.exists(current.resolve(".git"))) {
                    return Optional.of(current.toAbsolutePath().normalize());
                }
                Path parent = current.getParent();
                if (parent == null || samePath(parent, current)) {
                    return Optional.empty();
                }
                current = parent;
            }
            return Optional.empty();
        }

        private static void addIfDirectory(Set<Path> dirs, Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                dirs.add(normalized);
            }
        }

        private static List<Skill> loadSkillsFromDirectory(Path skillsDir) throws IOException {
            List<Skill> loaded = new ArrayList<>();
            try (Stream<Path> entries = Files.list(skillsDir)) {
                List<Path> skillRoots = entries
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
                for (Path skillRoot : skillRoots) {
                    Optional<Path> skillFile = findSkillFile(skillRoot);
                    if (skillFile.isEmpty()) {
                        continue;
                    }
                    try {
                        String raw = Files.readString(skillFile.get(), StandardCharsets.UTF_8);
                        ParsedMarkdown parsed = ParsedMarkdown.parse(raw);
                        loaded.add(Skill.from(skillRoot, skillFile.get(), parsed));
                    } catch (IOException exception) {
                        throw exception;
                    } catch (RuntimeException exception) {
                        System.err.println("jclaude: skipping invalid skill " + skillFile.get() + ": " + exception.getMessage());
                    }
                }
            }
            return loaded;
        }

        private static Optional<Path> findSkillFile(Path skillRoot) throws IOException {
            try (Stream<Path> entries = Files.list(skillRoot)) {
                return entries
                        .filter(Files::isRegularFile)
                        .filter(path -> "skill.md".equals(path.getFileName().toString().toLowerCase()))
                        .findFirst();
            }
        }

        private static Path safeRealPath(Path path) {
            try {
                return path.toRealPath();
            } catch (IOException exception) {
                return null;
            }
        }

        private static boolean samePath(Path first, Path second) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    private static final class InteractiveLineReader implements AutoCloseable {
        private static final int MAX_SUGGESTIONS = 8;
        private static final int MAX_HISTORY_ENTRIES = 200;
        private static final List<SlashCommandInfo> BUILTIN_COMMANDS = List.of(
                new SlashCommandInfo("help", "Show interactive help", Optional.empty(), false),
                new SlashCommandInfo("status", "Show working directory, config, provider, model, and skills", Optional.empty(), false),
                new SlashCommandInfo("config", "Show config and skill search paths", Optional.empty(), false),
                new SlashCommandInfo("model", "Show current model", Optional.empty(), false),
                new SlashCommandInfo("skills", "List loaded skills", Optional.empty(), false),
                new SlashCommandInfo("plan", "Enter read-only planning mode", Optional.of("[status|off]"), false),
                new SlashCommandInfo("clear", "Clear conversation history", Optional.empty(), false),
                new SlashCommandInfo("reset", "Clear conversation history", Optional.empty(), false),
                new SlashCommandInfo("exit", "Exit interactive mode", Optional.empty(), false),
                new SlashCommandInfo("quit", "Exit interactive mode", Optional.empty(), false)
        );

        private final SkillRegistry skills;
        private final BufferedReader fallbackReader;
        private final InputStreamReader rawReader;
        private final String originalTtySettings;
        private final int terminalColumns;
        private final List<String> historyEntries = new ArrayList<>();
        private boolean rawActive;

        private InteractiveLineReader(
                SkillRegistry skills,
                BufferedReader fallbackReader,
                InputStreamReader rawReader,
                String originalTtySettings,
                int terminalColumns
        ) {
            this.skills = skills;
            this.fallbackReader = fallbackReader;
            this.rawReader = rawReader;
            this.originalTtySettings = originalTtySettings;
            this.terminalColumns = terminalColumns;
        }

        static InteractiveLineReader open(SkillRegistry skills) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                return fallback(skills);
            }
            try {
                String originalSettings = ttyCommand("stty -g < /dev/tty").trim();
                return new InteractiveLineReader(
                        skills,
                        null,
                        new InputStreamReader(System.in, StandardCharsets.UTF_8),
                        originalSettings,
                        detectTerminalColumns()
                );
            } catch (IOException exception) {
                return fallback(skills);
            }
        }

        private static InteractiveLineReader fallback(SkillRegistry skills) {
            return new InteractiveLineReader(
                    skills,
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    null,
                    null,
                    detectTerminalColumns()
            );
        }

        String readLine(String prompt) throws IOException {
            if (fallbackReader != null) {
                System.out.print(prompt);
                return fallbackReader.readLine();
            }
            return readRawLine(prompt);
        }

        private String readRawLine(String prompt) throws IOException {
            enterRawMode();
            StringBuilder input = new StringBuilder();
            int cursorOffset = 0;
            List<InteractiveSuggestion> suggestions = List.of();
            int selectedSuggestion = -1;
            int historyIndex = historyEntries.size();
            String historyDraft = "";
            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);

            try {
                while (true) {
                    int character = rawReader.read();
                    if (character < 0) {
                        clearPrompt(prompt, input.toString());
                        return null;
                    }
                    if (character == 3) {
                        clearPrompt(prompt, input.toString());
                        return "/exit";
                    }
                    if (character == 1) {
                        if (cursorOffset != 0) {
                            cursorOffset = 0;
                            suggestions = suggestionsFor(input.toString(), cursorOffset);
                            selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        }
                        continue;
                    }
                    if (character == 4 && input.isEmpty()) {
                        clearPrompt(prompt, input.toString());
                        return null;
                    }
                    if (character == 4) {
                        if (deleteAtCursor(input, cursorOffset)) {
                            historyIndex = historyEntries.size();
                            historyDraft = "";
                            suggestions = suggestionsFor(input.toString(), cursorOffset);
                            selectedSuggestion = suggestions.isEmpty() ? -1 : 0;
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        }
                        continue;
                    }
                    if (character == 5) {
                        if (cursorOffset != input.length()) {
                            cursorOffset = input.length();
                            suggestions = suggestionsFor(input.toString(), cursorOffset);
                            selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        }
                        continue;
                    }
                    if (character == '\r' || character == '\n') {
                        String submitted = input.toString();
                        rememberHistory(submitted);
                        clearPrompt(prompt, submitted);
                        return submitted;
                    }
                    if (character == '\t') {
                        ApplyResult result = applySelectedSuggestion(input.toString(), suggestions, selectedSuggestion);
                        if (result.applied()) {
                            input.setLength(0);
                            input.append(result.input());
                            cursorOffset = result.cursorOffset();
                            historyIndex = historyEntries.size();
                            historyDraft = "";
                            suggestions = suggestionsFor(input.toString(), cursorOffset);
                            selectedSuggestion = suggestions.isEmpty() ? -1 : 0;
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        }
                        continue;
                    }
                    if (character == 127 || character == 8) {
                        int updatedCursorOffset = deleteBeforeCursor(input, cursorOffset);
                        if (updatedCursorOffset != cursorOffset) {
                            cursorOffset = updatedCursorOffset;
                            historyIndex = historyEntries.size();
                            historyDraft = "";
                            suggestions = suggestionsFor(input.toString(), cursorOffset);
                            selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        }
                        continue;
                    }
                    if (character == 27) {
                        EscapeKey key = readEscapeKey();
                        if (key == EscapeKey.UP && !suggestions.isEmpty()) {
                            selectedSuggestion = selectedSuggestion <= 0 ? suggestions.size() - 1 : selectedSuggestion - 1;
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        } else if (key == EscapeKey.DOWN && !suggestions.isEmpty()) {
                            selectedSuggestion = selectedSuggestion < 0 || selectedSuggestion >= suggestions.size() - 1 ? 0 : selectedSuggestion + 1;
                            render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                        } else if (key == EscapeKey.UP) {
                            if (!historyEntries.isEmpty()) {
                                if (historyIndex == historyEntries.size()) {
                                    historyDraft = input.toString();
                                }
                                if (historyIndex > 0) {
                                    historyIndex--;
                                    input.setLength(0);
                                    input.append(historyEntries.get(historyIndex));
                                    cursorOffset = input.length();
                                    suggestions = List.of();
                                    selectedSuggestion = -1;
                                    render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                                }
                            }
                        } else if (key == EscapeKey.DOWN) {
                            if (historyIndex < historyEntries.size()) {
                                historyIndex++;
                                input.setLength(0);
                                input.append(historyIndex == historyEntries.size() ? historyDraft : historyEntries.get(historyIndex));
                                cursorOffset = input.length();
                                suggestions = List.of();
                                selectedSuggestion = -1;
                                render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                            }
                        } else if (key == EscapeKey.LEFT) {
                            int updatedCursorOffset = previousCodePointOffset(input, cursorOffset);
                            if (updatedCursorOffset != cursorOffset) {
                                cursorOffset = updatedCursorOffset;
                                suggestions = suggestionsFor(input.toString(), cursorOffset);
                                selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                                render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                            }
                        } else if (key == EscapeKey.RIGHT) {
                            int updatedCursorOffset = nextCodePointOffset(input, cursorOffset);
                            if (updatedCursorOffset != cursorOffset) {
                                cursorOffset = updatedCursorOffset;
                                suggestions = suggestionsFor(input.toString(), cursorOffset);
                                selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                                render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                            }
                        } else if (key == EscapeKey.HOME) {
                            if (cursorOffset != 0) {
                                cursorOffset = 0;
                                suggestions = suggestionsFor(input.toString(), cursorOffset);
                                selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                                render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                            }
                        } else if (key == EscapeKey.END) {
                            if (cursorOffset != input.length()) {
                                cursorOffset = input.length();
                                suggestions = suggestionsFor(input.toString(), cursorOffset);
                                selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                                render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                            }
                        } else if (key == EscapeKey.DELETE) {
                            if (deleteAtCursor(input, cursorOffset)) {
                                historyIndex = historyEntries.size();
                                historyDraft = "";
                                suggestions = suggestionsFor(input.toString(), cursorOffset);
                                selectedSuggestion = suggestions.isEmpty() ? -1 : Math.min(Math.max(selectedSuggestion, 0), suggestions.size() - 1);
                                render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                            }
                        }
                        continue;
                    }

                    if (isPrintableCharacter(character)) {
                        cursorOffset = insertAtCursor(input, cursorOffset, character);
                        historyIndex = historyEntries.size();
                        historyDraft = "";
                        suggestions = suggestionsFor(input.toString(), cursorOffset);
                        selectedSuggestion = suggestions.isEmpty() ? -1 : 0;
                        render(prompt, input.toString(), cursorOffset, suggestions, selectedSuggestion);
                    }
                }
            } finally {
                restoreTerminal();
            }
        }

        private EscapeKey readEscapeKey() throws IOException {
            int first = readEscapeSequenceChar();
            if (first < 0) {
                return EscapeKey.OTHER;
            }
            if (first == 'O') {
                int second = readEscapeSequenceChar();
                return switch (second) {
                    case 'H' -> EscapeKey.HOME;
                    case 'F' -> EscapeKey.END;
                    default -> EscapeKey.OTHER;
                };
            }
            if (first != '[') {
                return EscapeKey.OTHER;
            }
            int second = readEscapeSequenceChar();
            if (second < 0) {
                return EscapeKey.OTHER;
            }
            if (Character.isDigit(second)) {
                StringBuilder sequence = new StringBuilder();
                sequence.append((char) second);
                while (sequence.length() < 12) {
                    int next = readEscapeSequenceChar();
                    if (next < 0) {
                        return EscapeKey.OTHER;
                    }
                    if (next == '~' || (next >= 'A' && next <= 'Z')) {
                        return csiKey(sequence.toString(), next);
                    }
                    sequence.append((char) next);
                }
                return EscapeKey.OTHER;
            }
            return switch (second) {
                case 'A' -> EscapeKey.UP;
                case 'B' -> EscapeKey.DOWN;
                case 'C' -> EscapeKey.RIGHT;
                case 'D' -> EscapeKey.LEFT;
                case 'H' -> EscapeKey.HOME;
                case 'F' -> EscapeKey.END;
                default -> EscapeKey.OTHER;
            };
        }

        private int readEscapeSequenceChar() throws IOException {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (rawReader.ready()) {
                    return rawReader.read();
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return -1;
        }

        private static EscapeKey csiKey(String parameters, int terminator) {
            if (terminator == 'A') {
                return EscapeKey.UP;
            }
            if (terminator == 'B') {
                return EscapeKey.DOWN;
            }
            if (terminator == 'C') {
                return EscapeKey.RIGHT;
            }
            if (terminator == 'D') {
                return EscapeKey.LEFT;
            }
            if (terminator == 'H') {
                return EscapeKey.HOME;
            }
            if (terminator == 'F') {
                return EscapeKey.END;
            }
            if (terminator != '~') {
                return EscapeKey.OTHER;
            }
            String firstParameter = parameters.split("[;:]", 2)[0];
            return switch (firstParameter) {
                case "1", "7" -> EscapeKey.HOME;
                case "3" -> EscapeKey.DELETE;
                case "4", "8" -> EscapeKey.END;
                default -> EscapeKey.OTHER;
            };
        }

        private List<InteractiveSuggestion> suggestionsFor(String input) {
            List<InteractiveSuggestion> slashSuggestions = slashSuggestions(input);
            if (!slashSuggestions.isEmpty()) {
                return slashSuggestions;
            }
            return fileSuggestions(input);
        }

        private List<InteractiveSuggestion> suggestionsFor(String input, int cursorOffset) {
            if (cursorOffset != input.length()) {
                return List.of();
            }
            return suggestionsFor(input);
        }

        private List<InteractiveSuggestion> slashSuggestions(String input) {
            if (!input.startsWith("/") || input.contains(" ")) {
                return List.of();
            }
            String query = input.substring(1).toLowerCase();
            List<InteractiveSuggestion> results = new ArrayList<>();
            for (SlashCommandInfo command : slashCommands()) {
                if (query.isBlank()
                        || command.name().toLowerCase().startsWith(query)
                        || command.description().toLowerCase().contains(query)) {
                    results.add(InteractiveSuggestion.command(
                            command.name(),
                            command.description(),
                            command.argumentHint().orElse(""),
                            command.skill()
                    ));
                }
            }
            results.sort((left, right) -> {
                boolean leftPrefix = left.name().toLowerCase().startsWith(query);
                boolean rightPrefix = right.name().toLowerCase().startsWith(query);
                if (leftPrefix != rightPrefix) {
                    return leftPrefix ? -1 : 1;
                }
                if (left.skill() != right.skill()) {
                    return left.skill() ? 1 : -1;
                }
                return left.name().compareTo(right.name());
            });
            return limit(results);
        }

        private List<SlashCommandInfo> slashCommands() {
            List<SlashCommandInfo> commands = new ArrayList<>(BUILTIN_COMMANDS);
            for (Skill skill : skills.skills) {
                if (!skill.userInvocable()) {
                    continue;
                }
                commands.add(new SlashCommandInfo(
                        skill.name(),
                        skill.description(),
                        skill.argumentHint(),
                        true
                ));
            }
            return commands;
        }

        private List<InteractiveSuggestion> fileSuggestions(String input) {
            Optional<AtToken> token = AtToken.find(input);
            if (token.isEmpty()) {
                return List.of();
            }
            AtToken atToken = token.get();
            ParsedPathToken parsed = ParsedPathToken.parse(atToken.searchText());
            if (!Files.isDirectory(parsed.directory())) {
                return List.of();
            }

            boolean includeHidden = parsed.prefix().startsWith(".");
            List<InteractiveSuggestion> results = new ArrayList<>();
            try (Stream<Path> entries = Files.list(parsed.directory())) {
                entries
                        .filter(path -> includeHidden || !path.getFileName().toString().startsWith("."))
                        .filter(path -> path.getFileName().toString().toLowerCase().startsWith(parsed.prefix().toLowerCase()))
                        .sorted((left, right) -> {
                            boolean leftDir = Files.isDirectory(left);
                            boolean rightDir = Files.isDirectory(right);
                            if (leftDir != rightDir) {
                                return leftDir ? -1 : 1;
                            }
                            return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
                        })
                        .limit(MAX_SUGGESTIONS)
                        .forEach(path -> {
                            boolean directory = Files.isDirectory(path);
                            String displayPath = parsed.displayPrefix() + path.getFileName() + (directory ? "/" : "");
                            results.add(InteractiveSuggestion.path(
                                    displayPath,
                                    "",
                                    directory ? SuggestionKind.DIRECTORY : SuggestionKind.FILE,
                                    atToken.start(),
                                    input.length(),
                                    atToken.quoted()
                            ));
                        });
            } catch (IOException ignored) {
                return List.of();
            }
            return results;
        }

        private ApplyResult applySelectedSuggestion(String input, List<InteractiveSuggestion> suggestions, int selectedSuggestion) {
            if (suggestions.isEmpty()) {
                return new ApplyResult(input, false, input.length());
            }
            int index = selectedSuggestion < 0 ? 0 : selectedSuggestion;
            if (index >= suggestions.size()) {
                index = 0;
            }
            InteractiveSuggestion suggestion = suggestions.get(index);
            if (suggestion.kind() == SuggestionKind.COMMAND) {
                String newInput = "/" + suggestion.name() + " ";
                return new ApplyResult(newInput, true, newInput.length());
            }

            boolean needsQuotes = suggestion.quoted() || suggestion.name().contains(" ");
            String replacement;
            if (needsQuotes) {
                replacement = "@\"" + suggestion.name();
                if (suggestion.kind() == SuggestionKind.FILE) {
                    replacement += "\" ";
                }
            } else {
                replacement = "@" + suggestion.name() + (suggestion.kind() == SuggestionKind.FILE ? " " : "");
            }
            String newInput = input.substring(0, suggestion.replaceStart()) + replacement + input.substring(suggestion.replaceEnd());
            return new ApplyResult(newInput, true, suggestion.replaceStart() + replacement.length());
        }

        private static List<InteractiveSuggestion> limit(List<InteractiveSuggestion> suggestions) {
            if (suggestions.size() <= MAX_SUGGESTIONS) {
                return suggestions;
            }
            return List.copyOf(suggestions.subList(0, MAX_SUGGESTIONS));
        }

        private static int insertAtCursor(StringBuilder input, int cursorOffset, int codePoint) {
            int safeCursorOffset = Math.max(0, Math.min(cursorOffset, input.length()));
            input.insert(safeCursorOffset, new String(Character.toChars(codePoint)));
            return safeCursorOffset + Character.charCount(codePoint);
        }

        private static int deleteBeforeCursor(StringBuilder input, int cursorOffset) {
            int safeCursorOffset = Math.max(0, Math.min(cursorOffset, input.length()));
            if (safeCursorOffset == 0) {
                return safeCursorOffset;
            }
            int previousOffset = previousCodePointOffset(input, safeCursorOffset);
            input.delete(previousOffset, safeCursorOffset);
            return previousOffset;
        }

        private static boolean deleteAtCursor(StringBuilder input, int cursorOffset) {
            int safeCursorOffset = Math.max(0, Math.min(cursorOffset, input.length()));
            if (safeCursorOffset >= input.length()) {
                return false;
            }
            int nextOffset = nextCodePointOffset(input, safeCursorOffset);
            input.delete(safeCursorOffset, nextOffset);
            return true;
        }

        private void rememberHistory(String input) {
            if (input.trim().isEmpty()) {
                return;
            }
            if (!historyEntries.isEmpty() && historyEntries.get(historyEntries.size() - 1).equals(input)) {
                return;
            }
            historyEntries.add(input);
            if (historyEntries.size() > MAX_HISTORY_ENTRIES) {
                historyEntries.remove(0);
            }
        }

        private static boolean isPrintableCharacter(int character) {
            return character >= 32 && character != 127;
        }

        private void render(String prompt, String input, int cursorOffset, List<InteractiveSuggestion> suggestions, int selectedSuggestion) {
            RenderedInput renderedInput = renderedInput(prompt, input, cursorOffset);
            System.out.print("\r\033[J");
            System.out.print(prompt);
            System.out.print(renderedInput.leftMarker());
            System.out.print(renderedInput.visibleInput());
            for (int index = 0; index < suggestions.size(); index++) {
                InteractiveSuggestion suggestion = suggestions.get(index);
                System.out.print("\r\n");
                String row = (index == selectedSuggestion ? "› " : "  ") + suggestion.displayText();
                if (!suggestion.description().isBlank()) {
                    row += "  " + suggestion.description();
                }
                System.out.print(fitToColumns(row, terminalColumns));
            }
            if (!suggestions.isEmpty()) {
                System.out.print("\033[" + suggestions.size() + "A");
            }
            System.out.print("\r\033[" + renderedInput.cursorColumn() + "G");
            System.out.flush();
        }

        private RenderedInput renderedInput(String prompt, String input, int cursorOffset) {
            int safeCursorOffset = Math.max(0, Math.min(cursorOffset, input.length()));
            int columns = Math.max(20, terminalColumns);
            int lineBudget = Math.max(1, columns - 1);
            int promptWidth = displayWidth(prompt);
            int inputBudget = Math.max(1, lineBudget - promptWidth);
            if (displayWidth(input) <= inputBudget) {
                int cursorColumn = promptWidth + displayWidth(input.substring(0, safeCursorOffset)) + 1;
                return new RenderedInput("", input, clampCursorColumn(cursorColumn, columns));
            }

            int visibleStart = safeCursorOffset;
            int widthBeforeCursor = 0;
            while (visibleStart > 0) {
                int previousOffset = previousCodePointOffset(input, visibleStart);
                int codePoint = input.codePointAt(previousOffset);
                int markerWidth = previousOffset > 0 ? 1 : 0;
                int nextWidth = widthBeforeCursor + codePointWidth(codePoint) + markerWidth;
                if (nextWidth > inputBudget) {
                    break;
                }
                widthBeforeCursor += codePointWidth(codePoint);
                visibleStart = previousOffset;
            }

            String leftMarker = visibleStart > 0 ? "…" : "";
            int visibleBudget = Math.max(0, inputBudget - displayWidth(leftMarker));
            String visibleInput = sliceToDisplayWidth(input, visibleStart, visibleBudget);
            int cursorColumn = promptWidth + displayWidth(leftMarker) + displayWidth(input.substring(visibleStart, safeCursorOffset)) + 1;
            return new RenderedInput(leftMarker, visibleInput, clampCursorColumn(cursorColumn, columns));
        }

        private static int clampCursorColumn(int cursorColumn, int columns) {
            return Math.max(1, Math.min(cursorColumn, Math.max(1, columns)));
        }

        private static int previousCodePointOffset(CharSequence value, int offset) {
            int safeOffset = Math.max(0, Math.min(offset, value.length()));
            if (safeOffset == 0) {
                return 0;
            }
            int previousOffset = safeOffset - 1;
            if (previousOffset > 0
                    && Character.isLowSurrogate(value.charAt(previousOffset))
                    && Character.isHighSurrogate(value.charAt(previousOffset - 1))) {
                return previousOffset - 1;
            }
            return previousOffset;
        }

        private static int nextCodePointOffset(CharSequence value, int offset) {
            int safeOffset = Math.max(0, Math.min(offset, value.length()));
            if (safeOffset >= value.length()) {
                return value.length();
            }
            int nextOffset = safeOffset + 1;
            if (Character.isHighSurrogate(value.charAt(safeOffset))
                    && nextOffset < value.length()
                    && Character.isLowSurrogate(value.charAt(nextOffset))) {
                return nextOffset + 1;
            }
            return nextOffset;
        }

        private void clearPrompt(String prompt, String input) {
            System.out.print("\r\033[J");
            System.out.print(prompt);
            System.out.print(input);
            System.out.print("\r\n");
            System.out.flush();
        }

        private static String fitToColumns(String value, int maxColumns) {
            int budget = Math.max(20, maxColumns);
            StringBuilder builder = new StringBuilder();
            int width = 0;
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                int characterWidth = codePointWidth(codePoint);
                if (width + characterWidth > budget - 1) {
                    builder.append('…');
                    return builder.toString();
                }
                builder.appendCodePoint(codePoint);
                width += characterWidth;
                offset += Character.charCount(codePoint);
            }
            return builder.toString();
        }

        private static String sliceToDisplayWidth(String value, int startOffset, int maxColumns) {
            if (maxColumns <= 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            int width = 0;
            for (int offset = Math.max(0, Math.min(startOffset, value.length())); offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                int characterWidth = codePointWidth(codePoint);
                if (width + characterWidth > maxColumns) {
                    if (width < maxColumns) {
                        builder.append('…');
                    }
                    return builder.toString();
                }
                builder.appendCodePoint(codePoint);
                width += characterWidth;
                offset += Character.charCount(codePoint);
            }
            return builder.toString();
        }

        private static int displayWidth(String value) {
            int width = 0;
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                width += codePointWidth(codePoint);
                offset += Character.charCount(codePoint);
            }
            return width;
        }

        private static int codePointWidth(int codePoint) {
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.FORMAT
                    || Character.isISOControl(codePoint)) {
                return 0;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return 2;
            }
            return 1;
        }

        private static int detectTerminalColumns() {
            String columns = System.getenv("COLUMNS");
            if (columns != null) {
                try {
                    int parsed = Integer.parseInt(columns.trim());
                    if (parsed >= 40) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                String output = ttyCommand("tput cols < /dev/tty").trim();
                int parsed = Integer.parseInt(output);
                if (parsed >= 40) {
                    return parsed;
                }
            } catch (IOException | NumberFormatException ignored) {
            }
            return 120;
        }

        private void enterRawMode() throws IOException {
            if (!rawActive && originalTtySettings != null && !originalTtySettings.isBlank()) {
                ttyCommand("stty raw -echo min 1 time 0 < /dev/tty");
                rawActive = true;
            }
        }

        private void restoreTerminal() {
            if (rawActive && originalTtySettings != null && !originalTtySettings.isBlank()) {
                try {
                    ttyCommand("stty " + originalTtySettings + " < /dev/tty");
                } catch (IOException ignored) {
                } finally {
                    rawActive = false;
                }
            }
        }

        private static String ttyCommand(String command) throws IOException {
            try {
                Process process = new ProcessBuilder("/bin/sh", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                byte[] output = process.getInputStream().readAllBytes();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new IOException("tty command failed: " + command);
                }
                return new String(output, StandardCharsets.UTF_8);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("tty command interrupted", exception);
            }
        }

        @Override
        public void close() {
            restoreTerminal();
        }
    }

    private enum EscapeKey {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END,
        DELETE,
        OTHER
    }

    private enum SuggestionKind {
        COMMAND,
        DIRECTORY,
        FILE
    }

    private record SlashCommandInfo(String name, String description, Optional<String> argumentHint, boolean skill) {
    }

    private record InteractiveSuggestion(
            String name,
            String description,
            SuggestionKind kind,
            int replaceStart,
            int replaceEnd,
            boolean quoted,
            boolean skill
    ) {
        static InteractiveSuggestion command(String name, String description, String argumentHint, boolean skill) {
            String details = argumentHint.isBlank() ? description : description + " " + argumentHint;
            return new InteractiveSuggestion(name, details, SuggestionKind.COMMAND, 0, 0, false, skill);
        }

        static InteractiveSuggestion path(
                String name,
                String description,
                SuggestionKind kind,
                int replaceStart,
                int replaceEnd,
                boolean quoted
        ) {
            return new InteractiveSuggestion(name, description, kind, replaceStart, replaceEnd, quoted, false);
        }

        String displayText() {
            return switch (kind) {
                case COMMAND -> "/" + name + (skill ? "  skill" : "");
                case DIRECTORY, FILE -> name;
            };
        }
    }

    private record ApplyResult(String input, boolean applied, int cursorOffset) {
    }

    private record RenderedInput(String leftMarker, String visibleInput, int cursorColumn) {
    }

    private record AtToken(int start, String searchText, boolean quoted) {
        static Optional<AtToken> find(String input) {
            for (int index = input.length() - 1; index >= 0; index--) {
                if (input.charAt(index) != '@') {
                    continue;
                }
                if (index > 0 && !Character.isWhitespace(input.charAt(index - 1))) {
                    continue;
                }
                String raw = input.substring(index + 1);
                boolean quoted = raw.startsWith("\"");
                String searchText = quoted ? raw.substring(1) : raw;
                if (!quoted && searchText.matches(".*\\s+.*")) {
                    return Optional.empty();
                }
                if (quoted && searchText.contains("\"")) {
                    return Optional.empty();
                }
                return Optional.of(new AtToken(index, searchText, quoted));
            }
            return Optional.empty();
        }
    }

    private record ParsedPathToken(Path directory, String prefix, String displayPrefix) {
        static ParsedPathToken parse(String token) {
            String normalized = token.replace('\\', '/');
            if (normalized.isBlank() || ".".equals(normalized)) {
                return new ParsedPathToken(Path.of("").toAbsolutePath().normalize(), "", "");
            }
            if ("~".equals(normalized)) {
                return new ParsedPathToken(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(), "", "~/");
            }
            boolean endsWithSlash = normalized.endsWith("/");
            int slashIndex = normalized.lastIndexOf('/');
            String directoryPart;
            String prefix;
            String displayPrefix;
            if (endsWithSlash) {
                directoryPart = normalized;
                prefix = "";
                displayPrefix = normalized;
            } else if (slashIndex >= 0) {
                directoryPart = normalized.substring(0, slashIndex + 1);
                prefix = normalized.substring(slashIndex + 1);
                displayPrefix = directoryPart;
            } else {
                directoryPart = "";
                prefix = normalized;
                displayPrefix = "";
            }
            Path directory = resolvePath(directoryPart.isBlank() ? "." : directoryPart);
            return new ParsedPathToken(directory, prefix, displayPrefix);
        }

        private static Path resolvePath(String value) {
            String expanded = value;
            if (expanded.equals("~") || expanded.startsWith("~/")) {
                expanded = System.getProperty("user.home") + expanded.substring(1);
            }
            Path path = Path.of(expanded);
            if (!path.isAbsolute()) {
                path = Path.of("").toAbsolutePath().resolve(path);
            }
            return path.normalize();
        }
    }

    private record SkillInvocation(Skill skill, String args) {
        String expandedPrompt() {
            return skill.renderInvocation(args);
        }
    }

    private record ParsedInvocation(String name, String args) {
        static ParsedInvocation parse(String input) {
            String trimmed = input.trim();
            if (!trimmed.startsWith("/") || trimmed.length() == 1) {
                return null;
            }
            String withoutSlash = trimmed.substring(1);
            int separator = firstWhitespaceIndex(withoutSlash);
            if (separator < 0) {
                return new ParsedInvocation(withoutSlash, "");
            }
            String name = withoutSlash.substring(0, separator);
            String args = withoutSlash.substring(separator + 1).trim();
            return new ParsedInvocation(name, args);
        }

        private static int firstWhitespaceIndex(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isWhitespace(value.charAt(index))) {
                    return index;
                }
            }
            return -1;
        }
    }

    private record Skill(
            String name,
            String description,
            Optional<String> whenToUse,
            Optional<String> displayName,
            Optional<String> argumentHint,
            List<String> argumentNames,
            boolean userInvocable,
            boolean disableModelInvocation,
            Path skillRoot,
            Path filePath,
            String content
    ) {
        static Skill from(Path skillRoot, Path filePath, ParsedMarkdown parsed) {
            Map<String, String> frontmatter = parsed.frontmatter();
            String skillName = skillRoot.getFileName().toString();
            String description = firstNonBlank(
                    frontmatter.get("description"),
                    extractDescription(parsed.content(), skillName)
            );
            return new Skill(
                    skillName,
                    description,
                    Optional.ofNullable(firstNonBlank(frontmatter.get("when_to_use"), frontmatter.get("whenToUse"))),
                    Optional.ofNullable(frontmatter.get("name")),
                    Optional.ofNullable(firstNonBlank(frontmatter.get("argument-hint"), frontmatter.get("argument_hint"))),
                    parseArgumentNames(frontmatter.get("arguments")),
                    parseBoolean(frontmatter.get("user-invocable"), true),
                    parseBoolean(frontmatter.get("disable-model-invocation"), false),
                    skillRoot.toAbsolutePath().normalize(),
                    filePath.toAbsolutePath().normalize(),
                    parsed.content().trim()
            );
        }

        String renderInvocation(String args) {
            String body = substituteArguments(content, args, argumentNames);
            String skillDir = skillRoot.toString().replace('\\', '/');
            body = body.replace("${CLAUDE_SKILL_DIR}", skillDir)
                    .replace("${JCLAUDE_SKILL_DIR}", skillDir)
                    .replace("${CLAUDE_SESSION_ID}", SESSION_ID)
                    .replace("${JCLAUDE_SESSION_ID}", SESSION_ID);

            return """
                    <jclaude_skill_invocation>
                    <skill_name>%s</skill_name>
                    <skill_path>%s</skill_path>
                    <arguments>%s</arguments>
                    </jclaude_skill_invocation>

                    Follow the local skill instructions below for the user's request.

                    Base directory for this skill: %s

                    %s
                    """.formatted(name, filePath, escapeXml(args), skillRoot, body);
        }

        private static String substituteArguments(String content, String args, List<String> argumentNames) {
            if (args == null) {
                return content;
            }
            String original = content;
            List<String> parsedArgs = parseArguments(args);
            String substituted = content;
            for (int index = 0; index < argumentNames.size(); index++) {
                String name = argumentNames.get(index);
                String value = index < parsedArgs.size() ? parsedArgs.get(index) : "";
                substituted = Pattern.compile("\\$" + Pattern.quote(name) + "(?![\\[\\w])")
                        .matcher(substituted)
                        .replaceAll(Matcher.quoteReplacement(value));
            }
            substituted = replaceIndexedArgument(substituted, "\\$ARGUMENTS\\[(\\d+)]", parsedArgs);
            substituted = replaceIndexedArgument(substituted, "\\$(\\d+)(?!\\w)", parsedArgs);
            substituted = substituted.replace("$ARGUMENTS", args);
            if (substituted.equals(original) && !args.isBlank()) {
                substituted = substituted + System.lineSeparator() + System.lineSeparator() + "ARGUMENTS: " + args;
            }
            return substituted;
        }

        private static String replaceIndexedArgument(String value, String regex, List<String> args) {
            Matcher matcher = Pattern.compile(regex).matcher(value);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                String replacement = index < args.size() ? args.get(index) : "";
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(buffer);
            return buffer.toString();
        }

        private static List<String> parseArguments(String args) {
            List<String> parsed = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            for (int index = 0; index < args.length(); index++) {
                char character = args.charAt(index);
                if (character == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote;
                    continue;
                }
                if (character == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote;
                    continue;
                }
                if (Character.isWhitespace(character) && !inSingleQuote && !inDoubleQuote) {
                    if (!current.isEmpty()) {
                        parsed.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
                current.append(character);
            }
            if (!current.isEmpty()) {
                parsed.add(current.toString());
            }
            return parsed;
        }

        private static List<String> parseArgumentNames(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            String normalized = value.replace("[", "")
                    .replace("]", "")
                    .replace(",", " ");
            List<String> names = new ArrayList<>();
            for (String part : normalized.split("\\s+")) {
                String name = unquote(part.trim());
                if (!name.isBlank() && !name.matches("\\d+")) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        }

        private static String extractDescription(String content, String skillName) {
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("```")) {
                    continue;
                }
                trimmed = trimmed.replaceFirst("^#+\\s*", "")
                        .replaceFirst("^[-*]\\s*", "")
                        .trim();
                if (!trimmed.isBlank()) {
                    return trimmed.length() > 160 ? trimmed.substring(0, 159) + "…" : trimmed;
                }
            }
            return "Skill " + skillName;
        }

        private static boolean parseBoolean(String value, boolean defaultValue) {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return switch (value.trim().toLowerCase()) {
                case "true", "yes", "y", "1", "on" -> true;
                case "false", "no", "n", "0", "off" -> false;
                default -> defaultValue;
            };
        }
    }

    private record ParsedMarkdown(Map<String, String> frontmatter, String content) {
        static ParsedMarkdown parse(String raw) {
            Matcher matcher = FRONTMATTER_PATTERN.matcher(raw);
            if (!matcher.matches()) {
                return new ParsedMarkdown(Map.of(), raw);
            }
            return new ParsedMarkdown(parseFrontmatter(matcher.group(1)), matcher.group(2));
        }

        private static Map<String, String> parseFrontmatter(String text) {
            Map<String, String> values = new LinkedHashMap<>();
            String currentKey = null;
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }
                if ((line.startsWith(" ") || line.startsWith("\t")) && currentKey != null) {
                    if (trimmed.startsWith("- ")) {
                        appendFrontmatterValue(values, currentKey, trimmed.substring(2));
                    } else {
                        appendFrontmatterValue(values, currentKey, trimmed);
                    }
                    continue;
                }
                int colonIndex = line.indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }
                currentKey = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                if ("|".equals(value) || ">".equals(value)) {
                    values.put(currentKey, "");
                } else {
                    values.put(currentKey, unquote(value));
                }
            }
            return values;
        }

        private static void appendFrontmatterValue(Map<String, String> values, String key, String value) {
            String cleaned = unquote(value);
            if (cleaned.isBlank()) {
                return;
            }
            String existing = values.get(key);
            values.put(key, existing == null || existing.isBlank() ? cleaned : existing + " " + cleaned);
        }
    }

    private record EffectiveConfig(
            Provider provider,
            String model,
            Optional<String> baseUrl,
            String outputFormat,
            Optional<String> apiKey,
            SkillRegistry skills
    ) {
    }

    private record ChatMessage(String role, String content, List<ImageAttachment> images) {
        ChatMessage(String role, String content) {
            this(role, content, List.of());
        }

        boolean hasImages() {
            return !images.isEmpty();
        }
    }

    private record ImageAttachment(Path path, String mediaType, String base64) {
        ImageAttachment openAiCompatible() throws IOException {
            if (!"image/webp".equals(mediaType)) {
                return this;
            }
            return convertWebpToPng();
        }

        private ImageAttachment convertWebpToPng() throws IOException {
            Path tempDir = Files.createTempDirectory("jclaude-webp-");
            Path pngPath = tempDir.resolve("image.png");
            try {
                Process process = new ProcessBuilder(
                        "sips",
                        "-s",
                        "format",
                        "png",
                        path.toString(),
                        "--out",
                        pngPath.toString()
                )
                        .redirectErrorStream(true)
                        .start();
                String output;
                try (InputStream processOutput = process.getInputStream()) {
                    output = new String(processOutput.readAllBytes(), StandardCharsets.UTF_8);
                }
                int exitCode;
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while converting WebP image to PNG", exception);
                }
                if (exitCode != 0 || !Files.isRegularFile(pngPath)) {
                    String details = output.isBlank() ? "" : ": " + output.trim();
                    throw new CliException("Failed to convert WebP image to PNG for OpenAI provider" + details);
                }
                String convertedBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(pngPath));
                return new ImageAttachment(path, "image/png", convertedBase64);
            } catch (IOException exception) {
                throw new IOException("Failed to convert WebP image to PNG for OpenAI provider using macOS sips: "
                        + exception.getMessage(), exception);
            } finally {
                deleteTemporaryPath(pngPath);
                deleteTemporaryPath(tempDir);
            }
        }

        private static void deleteTemporaryPath(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }

        static Optional<ImageAttachment> fromPath(String rawPath) throws IOException {
            Path path = resolveUserPath(rawPath);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            Optional<String> mediaType = imageMediaType(path);
            if (mediaType.isEmpty()) {
                return Optional.empty();
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            return Optional.of(new ImageAttachment(path, mediaType.get(), base64));
        }

        private static Path resolveUserPath(String rawPath) {
            return resolveLocalPath(rawPath);
        }

        private static Optional<String> imageMediaType(Path path) {
            String filename = path.getFileName().toString().toLowerCase();
            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                return Optional.of("image/jpeg");
            }
            if (filename.endsWith(".png")) {
                return Optional.of("image/png");
            }
            if (filename.endsWith(".gif")) {
                return Optional.of("image/gif");
            }
            if (filename.endsWith(".webp")) {
                return Optional.of("image/webp");
            }
            return Optional.empty();
        }
    }

    private record PromptInput(String text, List<ImageAttachment> images) {
        static PromptInput parse(String input) throws IOException {
            List<InputToken> tokens = InputToken.parse(input);
            List<ImageAttachment> images = new ArrayList<>();
            List<String> textTokens = new ArrayList<>();
            for (InputToken token : tokens) {
                Optional<ImageAttachment> image = ImageAttachment.fromPath(token.value());
                if (image.isPresent()) {
                    images.add(image.get());
                } else {
                    textTokens.add(token.value());
                }
            }
            String text = String.join(" ", textTokens).trim();
            if (text.isBlank() && !images.isEmpty()) {
                text = "Describe the image.";
            }
            String fileReferenceContext = fileReferenceContext(input);
            if (!fileReferenceContext.isBlank()) {
                text = text.isBlank() ? fileReferenceContext : text + System.lineSeparator() + System.lineSeparator() + fileReferenceContext;
            }
            return new PromptInput(text, List.copyOf(images));
        }

        ChatMessage toChatMessage() {
            return new ChatMessage("user", text, images);
        }

        private static String fileReferenceContext(String input) throws IOException {
            Matcher matcher = AT_FILE_REFERENCE_PATTERN.matcher(input);
            Map<String, AtFileReference> references = new LinkedHashMap<>();
            while (matcher.find()) {
                String raw = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                AtFileReference reference = AtFileReference.parse(raw);
                String key = reference.path() + ":" + reference.lineStart().orElse(null) + ":" + reference.lineEnd().orElse(null);
                references.putIfAbsent(key, reference);
            }
            if (references.isEmpty()) {
                return "";
            }

            StringBuilder builder = new StringBuilder("用户 @ 提到的本地文件引用：");
            for (AtFileReference reference : references.values()) {
                if (isImagePath(reference.path())) {
                    continue;
                }
                builder.append(System.lineSeparator()).append(renderReference(reference));
            }
            return builder.toString().equals("用户 @ 提到的本地文件引用：") ? "" : builder.toString();
        }

        private static String renderReference(AtFileReference reference) throws IOException {
            Path path = reference.path();
            String header = "<jclaude_file_reference path=\"%s\" absolute_path=\"%s\">"
                    .formatted(escapeXml(reference.original()), escapeXml(path.toString()));
            if (!Files.exists(path)) {
                return header + System.lineSeparator()
                        + "文件不存在。模型可以使用 Read 工具读取该路径并得到同样的错误。"
                        + System.lineSeparator()
                        + "</jclaude_file_reference>";
            }
            if (Files.isDirectory(path)) {
                return header + System.lineSeparator()
                        + renderDirectoryReference(path)
                        + "</jclaude_file_reference>";
            }
            if (!Files.isRegularFile(path)) {
                return header + System.lineSeparator()
                        + "路径不是普通文件。"
                        + System.lineSeparator()
                        + "</jclaude_file_reference>";
            }
            long size = Files.size(path);
            if (size > MAX_TOOL_TEXT_FILE_BYTES) {
                return header + System.lineSeparator()
                        + "文件过大，未内联（" + size + " 字节）。请使用 Read 工具配合 offset 和 limit 分段读取。"
                        + System.lineSeparator()
                        + "</jclaude_file_reference>";
            }
            String content = normalizeLineEndings(Files.readString(path, StandardCharsets.UTF_8));
            if (content.indexOf('\0') >= 0) {
                return header + System.lineSeparator()
                        + "文件看起来是二进制文件。"
                        + System.lineSeparator()
                        + "</jclaude_file_reference>";
            }
            List<String> lines = splitLines(content);
            int offset = reference.lineStart().orElse(1);
            int limit = reference.lineEnd()
                    .map(end -> Math.max(1, end - offset + 1))
                    .orElse(MAX_TOOL_READ_LINES);
            int fromIndex = Math.min(Math.max(0, offset - 1), lines.size());
            int toIndex = Math.min(lines.size(), fromIndex + limit);
            StringBuilder result = new StringBuilder(header).append(System.lineSeparator());
            if (lines.isEmpty()) {
                result.append("<system-reminder>提醒：文件存在，但内容为空。</system-reminder>");
            } else {
                result.append(formatNumberedLines(new ArrayList<>(lines.subList(fromIndex, toIndex)), offset));
                if (toIndex < lines.size()) {
                    result.append("<system-reminder>文件引用仅展示部分内容：当前展示第 ")
                            .append(offset)
                            .append("-")
                            .append(toIndex)
                            .append(" 行，共 ")
                            .append(lines.size())
                            .append(" 行。</system-reminder>");
                }
            }
            return result.append(System.lineSeparator()).append("</jclaude_file_reference>").toString();
        }

        private static String renderDirectoryReference(Path path) throws IOException {
            StringBuilder builder = new StringBuilder("目录：").append(path).append(System.lineSeparator());
            try (Stream<Path> entries = Files.list(path)) {
                entries
                        .sorted((left, right) -> {
                            boolean leftDir = Files.isDirectory(left);
                            boolean rightDir = Files.isDirectory(right);
                            if (leftDir != rightDir) {
                                return leftDir ? -1 : 1;
                            }
                            return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
                        })
                        .limit(100)
                        .forEach(entry -> builder.append(Files.isDirectory(entry) ? "[dir]  " : "[file] ")
                                .append(entry.getFileName())
                                .append(System.lineSeparator()));
            }
            return builder.toString();
        }
    }

    private record InputToken(String value) {
        static List<InputToken> parse(String input) {
            List<InputToken> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            for (int index = 0; index < input.length(); index++) {
                char character = input.charAt(index);
                if (character == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote;
                    continue;
                }
                if (character == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote;
                    continue;
                }
                if (Character.isWhitespace(character) && !inSingleQuote && !inDoubleQuote) {
                    if (!current.isEmpty()) {
                        tokens.add(new InputToken(current.toString()));
                        current.setLength(0);
                    }
                    continue;
                }
                current.append(character);
            }
            if (!current.isEmpty()) {
                tokens.add(new InputToken(current.toString()));
            }
            return tokens;
        }
    }

    private record CliRequest(
            boolean printMode,
            boolean help,
            boolean version,
            Provider provider,
            Optional<String> subcommand,
            List<String> positionals,
            Map<String, String> options
    ) {
        String prompt() {
            if (subcommand.isPresent()) {
                return "";
            }
            return String.join(" ", positionals);
        }

        Optional<String> option(String name) {
            return Optional.ofNullable(options.get(name));
        }
    }

    private static final class CliParser {
        private static final Map<String, String> SHORT_OPTIONS = Map.of(
                "-p", "print",
                "-h", "help",
                "-v", "version",
                "-c", "continue",
                "-r", "resume"
        );
        private static final Set<String> VALUE_OPTIONS = Set.of(
                "output-format", "input-format", "model", "provider", "base-url", "allowed-tools", "disallowed-tools",
                "mcp-config", "permission-mode", "resume", "settings", "add-dir", "agents"
        );

        static CliRequest parse(String[] args) {
            Map<String, String> options = new LinkedHashMap<>();
            List<String> positionals = new ArrayList<>();
            boolean optionsEnded = false;

            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (optionsEnded) {
                    positionals.add(arg);
                    continue;
                }
                if ("--".equals(arg)) {
                    optionsEnded = true;
                    continue;
                }
                if (arg.startsWith("--")) {
                    String raw = arg.substring(2);
                    String name;
                    String value;
                    int equalsIndex = raw.indexOf('=');
                    if (equalsIndex >= 0) {
                        name = raw.substring(0, equalsIndex);
                        value = raw.substring(equalsIndex + 1);
                    } else {
                        name = raw;
                        if (VALUE_OPTIONS.contains(name)) {
                            if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
                                throw new CliException("Option --" + name + " requires a value");
                            }
                            value = args[++index];
                        } else {
                            value = "true";
                        }
                    }
                    options.put(name, value);
                    continue;
                }
                if (arg.startsWith("-") && arg.length() > 1) {
                    String name = SHORT_OPTIONS.get(arg);
                    if (name == null) {
                        throw new CliException("Unknown option: " + arg);
                    }
                    if (VALUE_OPTIONS.contains(name) && index + 1 < args.length && !args[index + 1].startsWith("-")) {
                        options.put(name, args[++index]);
                    } else {
                        options.put(name, "true");
                    }
                    continue;
                }
                positionals.add(arg);
            }

            Optional<String> subcommand = positionals.isEmpty() || !KNOWN_SUBCOMMANDS.contains(positionals.getFirst())
                    ? Optional.empty()
                    : Optional.of(positionals.getFirst());
            return new CliRequest(
                    Boolean.parseBoolean(options.getOrDefault("print", "false")),
                    Boolean.parseBoolean(options.getOrDefault("help", "false")),
                    Boolean.parseBoolean(options.getOrDefault("version", "false")),
                    Provider.parse(options.getOrDefault("provider", firstNonBlank(System.getenv("JCLAUDE_PROVIDER"), "anthropic"))),
                    subcommand,
                    List.copyOf(positionals),
                    Map.copyOf(options)
            );
        }
    }

    private static final class CliException extends RuntimeException {
        CliException(String message) {
            super(Objects.requireNonNull(message));
        }
    }
}
