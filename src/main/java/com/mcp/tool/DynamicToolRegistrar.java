package com.mcp.tool;

import com.mcp.scanner.ApiParamSchema;
import com.mcp.scanner.CometApiSchemaLoader;
import com.mcp.scanner.FieldDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 MCP Tool 注册器。
 * <p>
 * 实现 {@link ToolCallbackProvider} 接口，Spring AI 在初始化 MCP Server 时
 * 会自动调用 {@link #getToolCallbacks()} 获取 Tool。
 * <p>
 * 与旧版本不同，本版本直接依赖 Comet 平台
 * 获取所有接口信息，并注册为 <b>单个统一 Tool</b>（invokeBusinessApi），
 * 通过 apiCode 参数选择具体调用哪个接口。
 * <p>
 * 这样做的目的：避免大量 Tool 导致 LLM 选择困难、盲目试错发请求。
 */
@Component
public class DynamicToolRegistrar implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolRegistrar.class);

    private static final String UNIFIED_TOOL_NAME = "invokeBusinessApi";

    private final HttpForwarder httpForwarder;
    private final ObjectMapper objectMapper;

    /** 参数 Schema 加载器：从 Comet 平台获取接口列表 */
    private final CometApiSchemaLoader cometLoader;

    /**
     * apiCode → ApiParamSchema 动态查找表。
     * <p>
     * 使用 ConcurrentHashMap 以支持后台加载未完成时的按需动态增加条目。
     */
    private final ConcurrentHashMap<String, ApiParamSchema> codeToSchemaMap = new ConcurrentHashMap<>();

    public DynamicToolRegistrar(HttpForwarder httpForwarder,
                                @Autowired(required = false) CometApiSchemaLoader cometLoader,
                                ObjectMapper objectMapper) {
        this.httpForwarder = httpForwarder;
        this.cometLoader = cometLoader;
        this.objectMapper = objectMapper;
    }

    /** 搜索工具名称 */
    private static final String SEARCH_TOOL_NAME = "searchBusinessApi";

    @Override
    public ToolCallback[] getToolCallbacks() {
        // 1. 检查 Comet 是否可用
        if (cometLoader == null || !cometLoader.isLoaded()) {
            if (cometLoader != null && cometLoader.isLoadingInProgress()) {
                // 多服务异步模式：后台加载未完成，工具将在加载完成后动态注册
                log.info("多服务 Schema 后台加载中 [{}]，"
                    + "getToolCallbacks() 返回空——等待 SchemaLoadCompleteEvent 事件触发动态注册",
                    cometLoader.getLoadSummary());
            } else if (cometLoader != null) {
                log.warn("Comet schema 加载失败或未配置，跳过工具注册");
            } else {
                log.warn("Comet schema loader 不可用（未配置 Comet），跳过工具注册");
            }
            return new ToolCallback[0];
        }

        // ======== 单服务同步模式：直接构建工具 ========

        Map<String, ApiParamSchema> allSchemas = cometLoader.getAllSchemas();
        log.info("Comet schema 总览: {} 个接口定义, 服务加载摘要: {}",
            allSchemas.size(), cometLoader.getLoadSummary());

        if (allSchemas.isEmpty()) {
            log.warn("Comet 接口列表为空，不注册任何工具");
            return new ToolCallback[0];
        }

        // 2. 构建 apiCode → ApiParamSchema 查找表
        rebuildCodeToSchemaMap(allSchemas);
        int codeCount = codeToSchemaMap.size();
        int skippedCount = allSchemas.size() - codeCount;
        log.info("查找表构建完成: {} 个 apiCode 可注册 (跳过 {} 个无 className/methodName 的接口)",
            codeCount, skippedCount);

        // 注册两个 Tool：统一调用 + 接口搜索
        ToolCallback unifiedTool = buildUnifiedTool(codeToSchemaMap);
        ToolCallback searchTool = buildSearchTool(codeToSchemaMap);
        log.info("✅ MCP 工具注册完成 (单服务模式): {} ({} 个接口, {} 个服务), {}",
            UNIFIED_TOOL_NAME, codeCount,
            cometLoader.getLoadSummary(),
            SEARCH_TOOL_NAME);

        return new ToolCallback[] { unifiedTool, searchTool };
    }


    /**
     * 重建 apiCode → ApiParamSchema 查找表。
     */
    private void rebuildCodeToSchemaMap(Map<String, ApiParamSchema> schemas) {
        codeToSchemaMap.clear();
        codeToSchemaMap.putAll(buildCodeToSchemaMap(schemas));
    }

    /**
     * 增量刷新 codeToSchema 查找表——从已加载的 Schema 中补充新条目。
     * 用于按需加载场景：后台加载完成后，将新增的 Schema 反映到查找表中。
     */
    private void refreshCodeToSchemaMap() {
        if (cometLoader == null) return;
        Map<String, ApiParamSchema> allSchemas = cometLoader.getAllSchemas();
        int before = codeToSchemaMap.size();
        Map<String, ApiParamSchema> newEntries = buildCodeToSchemaMap(allSchemas);
        codeToSchemaMap.putAll(newEntries);
        int after = codeToSchemaMap.size();
        if (after > before) {
            log.info("codeToSchema 查找表已刷新：{} → {} (新增 {})", before, after, after - before);
        }
    }

    // ========== 统一 Tool 构建 ==========

    /**
     * 构建 apiCode → ApiParamSchema 的查找表。
     * apiCode 格式：{sanitizedClassName}_{sanitizedMethodName}
     */
    private Map<String, ApiParamSchema> buildCodeToSchemaMap(Map<String, ApiParamSchema> schemas) {
        Map<String, ApiParamSchema> map = new LinkedHashMap<>();
        for (ApiParamSchema s : schemas.values()) {
            String code = buildApiCode(s);
            if (code != null) {
                map.put(code, s);
            } else {
                log.warn("Skipping schema with empty className/methodName: url={}, apiName={}",
                    s.url(), s.apiName());
            }
        }
        return map;
    }

    /** 生成 apiCode：className_methodName → core1200109445_runService */
    private String buildApiCode(ApiParamSchema schema) {
        String cn = schema.className();
        String mn = schema.methodName();
        if (cn == null || cn.isBlank() || mn == null || mn.isBlank()) return null;
        String code = sanitizeToolSegment(cn) + "_" + sanitizeToolSegment(mn);
        code = code.replaceAll("[^a-zA-Z0-9_]", "_")
                   .replaceAll("_+", "_")
                   .replaceAll("^_|_$", "");
        return code.isEmpty() ? null : code.toLowerCase();
    }

    /** 清洗工具名称段：camelCase → snake_case，去除非字母数字字符 */
    private String sanitizeToolSegment(String s) {
        if (s == null) return "";
        String result = s.replaceAll("([a-z])([A-Z])", "$1_$2");
        result = result.replaceAll("[^a-zA-Z0-9_]", "_");
        result = result.replaceAll("_+", "_");
        result = result.replaceAll("^_|_$", "");
        return result;
    }

    /**
     * 创建统一的 ToolCallback。
     * 该工具接受 apiCode（选择接口）+ params（业务参数），
     * 内部根据 apiCode 映射到具体的接口 URL 并转发请求。
     */
    private ToolCallback buildUnifiedTool(Map<String, ApiParamSchema> codeToSchema) {
        String description = buildUnifiedDescription(codeToSchema);
        String inputSchemaJson = buildUnifiedInputSchema(codeToSchema);

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name(UNIFIED_TOOL_NAME)
                    .description(description)
                    .inputSchema(inputSchemaJson)
                    .build();
            }

            @Override
            @SuppressWarnings("unchecked")
            public String call(String argumentJson) {
                try {
                    Map<String, Object> args = objectMapper.readValue(argumentJson, Map.class);

                    // 提取 apiCode
                    String apiCode = args != null ? (String) args.get("apiCode") : null;
                    if (apiCode == null || apiCode.isBlank()) {
                        return "错误: 缺少必要参数 'apiCode'。可用接口: "
                            + String.join(", ", codeToSchema.keySet());
                    }

                    // 查找对应的 Schema
                    ApiParamSchema schema = codeToSchema.get(apiCode);
                    if (schema == null) {
                        // 后台加载可能尚未完成，尝试按需同步加载
                        if (cometLoader != null && cometLoader.isLoadingInProgress()) {
                            log.info("apiCode [{}] 未命中，触发按需加载...", apiCode);
                            cometLoader.loadRemainingOnDemand();
                            refreshCodeToSchemaMap();
                            schema = codeToSchema.get(apiCode);
                        }
                    }
                    if (schema == null) {
                        return "错误: 未知的 apiCode '" + apiCode + "'。可用接口: "
                            + String.join(", ", codeToSchema.keySet());
                    }

                    // 提取业务参数
                    Map<String, Object> params = args.get("params") instanceof Map
                        ? (Map<String, Object>) args.get("params")
                        : Map.of();

                    // 从 Schema 中提取必输字段
                    List<String> requiredFields = schema.inputs().stream()
                        .filter(f -> f.required())
                        .map(f -> f.name())
                        .toList();

                    // 转发 POST 请求（含必输校验）
                    return httpForwarder.forwardPost(schema.url(), params, requiredFields);

                } catch (Exception e) {
                    log.error("Unified tool call failed: {}", e.getMessage(), e);
                    return "调用失败，请稍后重试。";
                }
            }

            @Override
            public String call(String argumentJson, ToolContext toolContext) {
                return call(argumentJson);
            }
        };
    }

    // ========== 接口搜索工具 ==========

    /**
     * 构建接口搜索工具 searchBusinessApi。
     * <p>
     * 在 apiName、remark、className、methodName、入参名和入参描述中多字段关键词搜索，
     * 返回匹配的接口详情（含入参信息）。LLM 不确定 apiCode 时应先调用此工具。
     */
    private ToolCallback buildSearchTool(Map<String, ApiParamSchema> codeToSchema) {
        String description = "搜索后端业务接口。根据关键词(如接口名、业务描述、功能名称)查找匹配的接口，"
            + "返回接口编码(apiCode)和详细参数信息。找到准确的 apiCode 后再调用 invokeBusinessApi。";

        String inputSchemaJson = "{\"type\":\"object\",\"properties\":{"
            + "\"keywords\":{\"type\":\"string\",\"description\":\"搜索关键词，支持多个关键词用空格分隔，如：贷款 查询\"}"
            + "},\"required\":[\"keywords\"]}";

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name(SEARCH_TOOL_NAME)
                    .description(description)
                    .inputSchema(inputSchemaJson)
                    .build();
            }

            @Override
            public String call(String argumentJson) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.readValue(argumentJson, Map.class);
                    String keywords = args != null ? (String) args.get("keywords") : null;
                    if (keywords == null || keywords.isBlank()) {
                        return "请提供搜索关键词。";
                    }
                    return searchApis(keywords.trim(), codeToSchema);
                } catch (Exception e) {
                    log.error("searchBusinessApi failed: {}", e.getMessage(), e);
                    return "搜索失败，请稍后重试。";
                }
            }

            @Override
            public String call(String argumentJson, ToolContext toolContext) {
                return call(argumentJson);
            }
        };
    }

    /**
     * 执行多字段关键词搜索，返回格式化的匹配结果。
     */
    private String searchApis(String keywords, Map<String, ApiParamSchema> codeToSchema) {
        String[] parts = keywords.toLowerCase().split("\\s+");

        // 评分: 每个匹配 +1 分
        List<ScoredResult> scored = new ArrayList<>();

        for (Map.Entry<String, ApiParamSchema> entry : codeToSchema.entrySet()) {
            String apiCode = entry.getKey();
            ApiParamSchema s = entry.getValue();
            int score = 0;

            // 在多个字段中搜索
            String searchableText = (
                (s.apiName() != null ? s.apiName() : "") + " " +
                (s.remark() != null ? s.remark() : "") + " " +
                (s.className() != null ? s.className() : "") + " " +
                (s.methodName() != null ? s.methodName() : "") + " " +
                apiCode + " "
            ).toLowerCase();

            // 入参名称和描述
            StringBuilder paramText = new StringBuilder();
            for (FieldDef f : s.inputs()) {
                paramText.append(f.name()).append(" ");
                if (f.description() != null) paramText.append(f.description()).append(" ");
            }
            String paramSearchable = paramText.toString().toLowerCase();

            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (searchableText.contains(part)) score++;
                if (paramSearchable.contains(part)) score++;
            }

            if (score > 0) {
                scored.add(new ScoredResult(apiCode, s, score));
            }
        }

        // 按分数降序排序，取前10
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        int limit = Math.min(scored.size(), 10);

        if (scored.isEmpty()) {
            return "未找到匹配 \"" + keywords + "\" 的接口。请尝试其他关键词。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(scored.size()).append(" 个匹配接口");
        if (scored.size() > limit) sb.append("，显示前 ").append(limit).append(" 个");
        sb.append(":\n\n");

        for (int i = 0; i < limit; i++) {
            ScoredResult r = scored.get(i);
            ApiParamSchema s = r.schema;
            sb.append(i + 1).append(". ").append(r.apiCode);
            if (s.apiName() != null && !s.apiName().isBlank()) {
                sb.append(" (").append(s.apiName()).append(")");
            }
            sb.append("\n");
            if (s.remark() != null && !s.remark().isBlank()) {
                sb.append("   描述: ").append(s.remark().replaceAll("\\s+", " ")).append("\n");
            }
            sb.append("   路径: ").append(s.url()).append("\n");

            // 必填参数
            List<FieldDef> required = s.inputs().stream()
                .filter(FieldDef::required).toList();
            List<FieldDef> optional = s.inputs().stream()
                .filter(f -> !f.required()).toList();

            if (!required.isEmpty()) {
                sb.append("   必填参数: ");
                sb.append(String.join(", ", required.stream()
                    .map(f -> {
                        String desc = f.description() != null && !f.description().isBlank()
                            ? "(" + f.description() + ")" : "";
                        return f.name() + desc;
                    }).toList()));
                sb.append("\n");
            }
            if (!optional.isEmpty()) {
                sb.append("   可选参数: ");
                sb.append(String.join(", ", optional.stream()
                    .limit(5)
                    .map(f -> {
                        String desc = f.description() != null && !f.description().isBlank()
                            ? "(" + f.description() + ")" : "";
                        return f.name() + desc;
                    }).toList()));
                if (optional.size() > 5) sb.append(" ...");
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("确定 apiCode 后，请调用 invokeBusinessApi 工具执行实际接口调用。");
        return sb.toString();
    }

    /** 搜索结果条目 */
    private static class ScoredResult {
        final String apiCode;
        final ApiParamSchema schema;
        final int score;
        ScoredResult(String apiCode, ApiParamSchema schema, int score) {
            this.apiCode = apiCode;
            this.schema = schema;
            this.score = score;
        }
    }

    // ========== 描述生成 ==========

    /** 每组最多展示的接口明细数，超过则折叠显示 */
    private static final int MAX_API_PER_GROUP = 5;

    /**
     * 构建统一 Tool 的描述。
     * <p>
     * 按业务模块分组展示接口列表，每条包含 apiCode、中文名、业务描述（remark）、
     * 以及核心入参摘要。末尾引导 LLM 先搜索后调用。
     */
    private String buildUnifiedDescription(Map<String, ApiParamSchema> codeToSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("调用后端业务接口（POST）。通过 apiCode 选择要调用的接口，");
        sb.append("在 params 中传入该接口的业务参数（即报文 body 内容）。\n\n");
        sb.append("**如果对使用哪个 apiCode 不确定，请先调用 searchBusinessApi 工具搜索接口，");
        sb.append("找到准确的 apiCode 后再调用本工具。**\n\n");
        sb.append("**重要规则：根据问题确定唯一的 apiCode 后只调用一次，不要遍历多个接口。**\n\n");

        // 按 URL 业务分组
        Map<String, List<Map.Entry<String, ApiParamSchema>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, ApiParamSchema> entry : codeToSchema.entrySet()) {
            String groupKey = extractGroupKey(entry.getValue());
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(entry);
        }

        sb.append("可用接口（共 ").append(codeToSchema.size()).append(" 个，按模块分组）:\n");
        for (Map.Entry<String, List<Map.Entry<String, ApiParamSchema>>> group : groups.entrySet()) {
            List<Map.Entry<String, ApiParamSchema>> members = group.getValue();
            members.sort(Map.Entry.comparingByKey());

            sb.append("\n【").append(group.getKey()).append("】(").append(members.size()).append(" 个):\n");
            int shown = 0;
            for (Map.Entry<String, ApiParamSchema> entry : members) {
                if (shown >= MAX_API_PER_GROUP) {
                    sb.append("  ... 另有 ").append(members.size() - MAX_API_PER_GROUP).append(" 个接口未列出\n");
                    break;
                }
                String code = entry.getKey();
                ApiParamSchema s = entry.getValue();
                // A1: apiCode (apiName) - remark
                sb.append("  - ").append(code);
                if (s.apiName() != null && !s.apiName().isBlank()) {
                    sb.append(" (").append(s.apiName()).append(")");
                }
                if (s.remark() != null && !s.remark().isBlank()) {
                    sb.append(" - ").append(s.remark().replaceAll("\\s+", " "));
                }
                sb.append("\n");
                // A2: 入参摘要（最多5个）
                List<FieldDef> inputs = s.inputs();
                if (!inputs.isEmpty()) {
                    sb.append("    入参: ");
                    List<String> paramParts = new ArrayList<>();
                    int paramCount = 0;
                    for (FieldDef f : inputs) {
                        if (paramCount >= 5) {
                            paramParts.add("...");
                            break;
                        }
                        StringBuilder p = new StringBuilder(f.name());
                        if (f.description() != null && !f.description().isBlank()) {
                            p.append("(").append(f.description()).append(")");
                        }
                        if (f.required()) {
                            p.append("[必填]");
                        }
                        paramParts.add(p.toString());
                        paramCount++;
                    }
                    sb.append(String.join(", ", paramParts));
                    if (inputs.size() > 5) {
                        sb.append(" 共").append(inputs.size()).append("个参数");
                    }
                    sb.append("\n");
                }
                shown++;
            }
        }

        sb.append("\n使用说明:\n");
        sb.append("1. 不确定用哪个 apiCode 时，先调用 searchBusinessApi 搜索\n");
        sb.append("2. 确定 apiCode 后，在 params 中传入对应的业务参数\n");
        sb.append("3. 如果不需要参数，可不传 params 或传空对象 {}\n");
        sb.append("4. **调用成功后直接根据返回数据回答用户，禁止继续调用其他接口**");

        return sb.toString();
    }

    /**
     * 从 URL 路径提取有业务含义的分组 key。
     * 优先取 URL 倒数第二段有意义单词作为组名（如 /pf/inq/xxx/loan/query → loan），
     * 兜底使用 className 字母前缀（如 core1200109445 → core）。
     */
    private String extractGroupKey(ApiParamSchema schema) {
        // 策略 1: 从 URL 路径中提取倒数第二段有业务含义的单词
        String url = schema.url();
        if (url != null) {
            String[] segments = url.split("/");
            for (int i = segments.length - 2; i >= 1; i--) {
                String seg = segments[i].trim();
                if (seg.length() >= 2 && seg.matches("[a-zA-Z]{2,}")) {
                    return seg;
                }
            }
        }
        // 策略 2: 兜底使用 className 字母前缀
        String cn = schema.className();
        if (cn == null || cn.isBlank()) return "其他";
        String alphaPrefix = cn.trim().replaceAll("[^a-zA-Z].*$", "");
        if (alphaPrefix.length() >= 2) return alphaPrefix;
        return cn.trim().substring(0, Math.min(3, cn.trim().length()));
    }

    // ========== 入参 JSON Schema 生成 ==========

    /** Schema 中 enum 列表截断阈值：超过该数量时不生成 enum，改为 description 提示 */
    private static final int ENUM_TRUNCATE_THRESHOLD = 100;

    /**
     * 构建统一 Tool 的 JSON Schema。
     * <p>
     * 结构：
     * <pre>
     * {
     *   "apiCode": "core1200109445_runService",  // 接口编码
     *   "params": { ... }                        // 业务参数
     * }
     * </pre>
     * 当接口数量超过 {@link #ENUM_TRUNCATE_THRESHOLD} 时，不生成 enum 列表（避免 Schema 过大），
     * 改为在 description 中提示 LLM 从可用接口列表中选择。
     */
    private String buildUnifiedInputSchema(Map<String, ApiParamSchema> codeToSchema) {
        List<String> sortedCodes = codeToSchema.keySet().stream()
            .sorted()
            .toList();

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // apiCode 字段
        Map<String, Object> apiCodeProp = new LinkedHashMap<>();
        apiCodeProp.put("type", "string");
        apiCodeProp.put("description", "接口编码，格式：{业务类名}_{方法名}，例如 " + sortedCodes.get(0));

        if (sortedCodes.size() <= ENUM_TRUNCATE_THRESHOLD) {
            // 接口数量适中→生成完整 enum 列表
            apiCodeProp.put("enum", sortedCodes);
        } else {
            // 接口过多→不在 enum 中列出，减少 Schema 大小
            apiCodeProp.put("description",
                "接口编码，格式：{业务类名}_{方法名}，共 " + sortedCodes.size()
                    + " 个可用接口。请参考 Tool description 中的接口列表选择正确的 apiCode。");
        }
        properties.put("apiCode", apiCodeProp);

        // params 字段
        Map<String, Object> paramsProp = new LinkedHashMap<>();
        paramsProp.put("type", "object");
        paramsProp.put("description", "接口业务参数（即报文 body 内容），根据选择的 apiCode 不同而不同");
        paramsProp.put("additionalProperties", true);
        properties.put("params", paramsProp);

        schema.put("properties", properties);
        schema.put("required", List.of("apiCode"));

        return toJsonString(schema);
    }

    // ========== 工具方法 ==========

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    private String toJsonString(Map<String, Object> map) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize schema to JSON", e);
            return "{}";
        }
    }
}
