package com.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.scanner.ApiParamSchema;
import com.mcp.scanner.CometApiSchemaLoader;
import com.mcp.scanner.FieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

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

    /** 接口路径白名单过滤器 */
    private final PathMatcher pathMatcher = new AntPathMatcher();
    private final Environment environment;

    /**
     * 读取并解析白名单路径配置（逗号分隔字符串 → Java List）。
     * 配置格式示例：/rb/nfin/interest/**,/ob/inq/system/branch/user
     */
    private List<String> getIncludePaths() {
        String raw = environment.getProperty("service.interface.filter.include-paths", "");
        if (raw.isBlank() || "".equals(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * 判断接口路径是否通过过滤。无白名单配置时默认放行所有接口。
     */
    private boolean isPathAllowed(String path) {
        List<String> includePaths = getIncludePaths();
        if (!includePaths.isEmpty()) {
            return includePaths.stream().anyMatch(p -> pathMatcher.match(p, path));
        }
        return true; // 未配置白名单时全部放行
    }

    /**
     * apiCode → ApiParamSchema 动态查找表。
     * <p>
     * 使用 ConcurrentHashMap 以支持后台加载未完成时的按需动态增加条目。
     */
    private final ConcurrentHashMap<String, ApiParamSchema> codeToSchemaMap = new ConcurrentHashMap<>();

    public DynamicToolRegistrar(HttpForwarder httpForwarder,
                                @Autowired(required = false) CometApiSchemaLoader cometLoader,
                                ObjectMapper objectMapper,
                                Environment environment) {
        this.httpForwarder = httpForwarder;
        this.cometLoader = cometLoader;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /** 搜索工具名称 */
    private static final String SEARCH_TOOL_NAME = "searchBusinessApi";

    /** 接口详情工具名称 */
    private static final String DETAIL_TOOL_NAME = "getApiDetail";

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (cometLoader == null) {
            log.warn("Comet schema loader 不可用（未配置 Comet），跳过工具注册");
            return new ToolCallback[0];
        }

        // 使用当前已加载的 Schema 构建查找表（可能是 partial，异步加载中也会返回非空工具）
        rebuildCodeToSchemaMap(cometLoader.getAllSchemas());

        log.info("构建 MCP 工具: {}, {}, {} (已加载 {} 个接口, 加载状态: {})",
            UNIFIED_TOOL_NAME, SEARCH_TOOL_NAME, DETAIL_TOOL_NAME, codeToSchemaMap.size(),
            cometLoader.isLoaded() ? "完成"
                : (cometLoader.isLoadingInProgress() ? "加载中" : "未加载"));

        ToolCallback unifiedTool = buildUnifiedTool();
        ToolCallback searchTool = buildSearchTool();
        ToolCallback detailTool = buildDetailTool();
        return new ToolCallback[] { unifiedTool, searchTool, detailTool };
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
    void refreshCodeToSchemaMap() {
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
        int skipped = 0;
        for (ApiParamSchema s : schemas.values()) {
            // 接口路径白名单过滤
            if (!isPathAllowed(s.url())) {
                skipped++;
                continue;
            }
            String code = buildApiCode(s);
            if (code != null) {
                map.put(code, s);
            } else {
                log.warn("Skipping schema with empty className/methodName: url={}, apiName={}",
                    s.url(), s.apiName());
            }
        }
        if (skipped > 0) {
            log.info("接口白名单过滤: 放行 {} 个, 过滤 {} 个", map.size(), skipped);
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
     * <p>
     * 注意：description 和 inputSchema 采用紧凑模式（不枚举接口列表），
     * 引导 LLM 先使用 searchBusinessApi 搜索再调用。工具内部引用
     * {@link #codeToSchemaMap} 类字段，后台加载完成后自动可见新接口。
     */
    private ToolCallback buildUnifiedTool() {
        String description = buildUnifiedDescription();
        String inputSchemaJson = buildUnifiedInputSchema();

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
                        return "错误: 缺少必要参数 'apiCode'。"
                            + "请先调用 searchBusinessApi 搜索获取 apiCode。";
                    }

                    // 查找对应的 Schema（类字段引用，后台加载完成后自动可见）
                    ApiParamSchema schema = codeToSchemaMap.get(apiCode);
                    if (schema == null) {
                        // 后台加载可能尚未完成，尝试按需同步加载
                        if (cometLoader != null && cometLoader.isLoadingInProgress()) {
                            log.info("apiCode [{}] 未命中，触发按需加载...", apiCode);
                            cometLoader.loadRemainingOnDemand();
                            refreshCodeToSchemaMap();
                            schema = codeToSchemaMap.get(apiCode);
                        }
                    }
                    if (schema == null) {
                        return "错误: 未知的 apiCode '" + apiCode + "'。"
                            + "请先调用 searchBusinessApi 搜索正确的 apiCode。";
                    }

                    // 提取业务参数
                    Map<String, Object> params = args.get("params") instanceof Map
                        ? (Map<String, Object>) args.get("params")
                        : Map.of();

                    // 转发 POST 请求（含必输/取值范围校验），传入完整字段定义，apiCode 用于推导 messageType/messageCode
                    return httpForwarder.forwardPost(schema.url(), params, schema.inputs(), apiCode);

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
    private ToolCallback buildSearchTool() {
        String description = "搜索后端业务接口。根据关键词(如接口名、业务描述、功能名称)查找匹配的接口，"
            + "返回接口编码(apiCode)和简要说明。找到准确的 apiCode 后，先调用 getApiDetail 获取该接口的完整参数，"
            + "再调用 invokeBusinessApi 执行调用。";

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
                    return searchApis(keywords.trim());
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
     * <p>
     * 搜索范围为全量已加载接口（含非白名单），白名单外的结果标注为不可调用。
     * 白名单接口加权优先展示。
     */
    private String searchApis(String keywords) {
        String[] parts = keywords.toLowerCase().split("\\s+");

        // 搜索范围：全量已加载 Schema（不含白名单过滤），确保非白名单接口也能被搜索到
        Map<String, ApiParamSchema> allSchemas = (cometLoader != null)
            ? cometLoader.getAllSchemas()
            : Map.of();

        List<ScoredResult> scored = new ArrayList<>();

        for (Map.Entry<String, ApiParamSchema> entry : allSchemas.entrySet()) {
            ApiParamSchema s = entry.getValue();
            String apiCode = buildApiCode(s);
            if (apiCode == null) continue;

            // 判断是否在白名单内（codeToSchemaMap 只包含白名单接口）
            boolean whitelisted = codeToSchemaMap.containsKey(apiCode);

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
                // 白名单接口加权，确保优先展示
                if (whitelisted) score += 100;
                scored.add(new ScoredResult(apiCode, s, score, whitelisted));
            }
        }

        // 按分数降序排序，取前5（紧凑列表，详情按需通过 getApiDetail 获取）
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        int limit = Math.min(scored.size(), 5);

        if (scored.isEmpty()) {
            return "未找到匹配 \"" + keywords + "\" 的接口。请尝试其他关键词。";
        }

        long whitelistedCount = scored.stream().filter(r -> r.whitelisted).count();

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(scored.size()).append(" 个匹配接口");
        sb.append("（其中 ").append(whitelistedCount).append(" 个可调用");
        long nonWhitelisted = scored.size() - whitelistedCount;
        if (nonWhitelisted > 0) {
            sb.append("，").append(nonWhitelisted).append(" 个未在白名单需添加配置");
        }
        sb.append("）");
        if (scored.size() > limit) sb.append("，显示前 ").append(limit).append(" 个");
        sb.append(":\n\n");

        // 紧凑列表：仅展示 apiCode / 接口名 / 描述，不展开参数树（参数详情由 getApiDetail 按需获取，节省 token）
        for (int i = 0; i < limit; i++) {
            ScoredResult r = scored.get(i);
            ApiParamSchema s = r.schema;
            sb.append(i + 1).append(". ");
            if (r.whitelisted) {
                sb.append("✅ ");
            } else {
                sb.append("⚠️ ");
            }
            sb.append(r.apiCode);
            if (s.apiName() != null && !s.apiName().isBlank()) {
                sb.append(" (").append(s.apiName()).append(")");
            }
            if (!r.whitelisted) {
                sb.append(" 【不可调用：未在白名单】");
            }
            if (s.remark() != null && !s.remark().isBlank()) {
                sb.append("\n   描述: ").append(s.remark().replaceAll("\\s+", " "));
            }
            sb.append("\n");
        }

        sb.append("\n确定要调用的接口后（仅 ✅ 标记的接口可调用），"
            + "请先调用 getApiDetail 获取该接口的完整参数信息，再调用 invokeBusinessApi 执行调用。");
        return sb.toString();
    }

    // ========== 接口详情工具 ==========

    /**
     * 构建接口详情工具 getApiDetail。
     * <p>
     * 根据 apiCode 返回单个接口的完整入参信息（必填/可选参数及类型、描述、取值范围、嵌套结构）。
     * 在 searchBusinessApi 确定 apiCode 后、调用 invokeBusinessApi 之前使用。
     * 相比在搜索结果中一次性返回多个接口的完整参数树，按需获取单接口详情可显著节省 token。
     */
    private ToolCallback buildDetailTool() {
        String description = "获取指定接口的完整入参信息。根据 searchBusinessApi 搜索到的 apiCode，"
            + "返回该接口的必填/可选参数（含类型、说明、取值范围、嵌套结构）。"
            + "确定参数后再调用 invokeBusinessApi 执行调用。";

        String inputSchemaJson = "{\"type\":\"object\",\"properties\":{"
            + "\"apiCode\":{\"type\":\"string\",\"description\":\"接口编码，格式：{业务类名}_{方法名}，由 searchBusinessApi 搜索获得\"}"
            + "},\"required\":[\"apiCode\"]}";

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name(DETAIL_TOOL_NAME)
                    .description(description)
                    .inputSchema(inputSchemaJson)
                    .build();
            }

            @Override
            public String call(String argumentJson) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = objectMapper.readValue(argumentJson, Map.class);
                    String apiCode = args != null ? (String) args.get("apiCode") : null;
                    if (apiCode == null || apiCode.isBlank()) {
                        return "请提供 apiCode 参数。可先调用 searchBusinessApi 搜索获取。";
                    }
                    return getApiDetailText(apiCode.trim());
                } catch (Exception e) {
                    log.error("getApiDetail failed: {}", e.getMessage(), e);
                    return "查询接口详情失败，请稍后重试。";
                }
            }

            @Override
            public String call(String argumentJson, ToolContext toolContext) {
                return call(argumentJson);
            }
        };
    }

    /**
     * 格式化单个接口的完整参数详情（供 getApiDetail 使用）。
     */
    private String getApiDetailText(String apiCode) {
        ApiParamSchema schema = findSchemaByApiCode(apiCode);
        if (schema == null) {
            return "未找到 apiCode \"" + apiCode + "\"。请先调用 searchBusinessApi 搜索正确的 apiCode。";
        }

        boolean whitelisted = codeToSchemaMap.containsKey(apiCode);

        StringBuilder sb = new StringBuilder();
        sb.append("接口: ").append(apiCode);
        if (schema.apiName() != null && !schema.apiName().isBlank()) {
            sb.append(" (").append(schema.apiName()).append(")");
        }
        if (!whitelisted) {
            sb.append(" 【不可调用：未在白名单】");
        }
        sb.append("\n");
        if (schema.remark() != null && !schema.remark().isBlank()) {
            sb.append("描述: ").append(schema.remark().replaceAll("\\s+", " ")).append("\n");
        }
        sb.append("路径: ").append(schema.url()).append("\n");

        // 参数（全量展示，含类型、描述、取值范围；数组/嵌套结构字段递归展开）
        List<FieldDef> required = schema.inputs().stream()
            .filter(FieldDef::required).toList();
        List<FieldDef> optional = schema.inputs().stream()
            .filter(f -> !f.required()).toList();

        if (!required.isEmpty()) {
            sb.append("必填参数:\n");
            for (FieldDef f : required) {
                sb.append(formatParamField(f, "  "));
                sb.append("\n");
            }
        }
        if (!optional.isEmpty()) {
            sb.append("可选参数:\n");
            for (FieldDef f : optional) {
                sb.append(formatParamField(f, "  "));
                sb.append("\n");
            }
        }

        if (whitelisted) {
            sb.append("\n请根据以上参数构造 params 后，调用 invokeBusinessApi 执行实际调用。");
        } else {
            sb.append("\n该接口未在白名单中，无法调用。如需调用请先添加 include-paths 白名单配置。");
        }
        return sb.toString();
    }

    /**
     * 按 apiCode 查找接口 Schema。
     * <p>
     * 优先在白名单查找表 codeToSchemaMap 中查找；未命中时遍历全量已加载 Schema
     * （含非白名单接口，用于展示详情并提示不可调用）。
     */
    private ApiParamSchema findSchemaByApiCode(String apiCode) {
        ApiParamSchema schema = codeToSchemaMap.get(apiCode);
        if (schema != null) return schema;
        if (cometLoader != null) {
            for (ApiParamSchema s : cometLoader.getAllSchemas().values()) {
                if (apiCode.equals(buildApiCode(s))) return s;
            }
        }
        return null;
    }

    /**
     * 递归格式化字段树：name(type) 描述 [取值范围: values]
     * <p>
     * 数组项或其他嵌套结构中的子字段以缩进层级递归展示。例：
     * <pre>
     * - rateArray(List) 利率数组
     *     - ccy(String(3)) 币种 [取值范围: D,I]
     *     - rate(BigDecimal) 利率
     * </pre>
     *
     * @param f     字段定义
     * @param indent 每行前缀缩进（由调用方传入，保证多行对齐）
     */
    private String formatParamField(FieldDef f, String indent) {
        StringBuilder sb = new StringBuilder();
        appendFieldTree(sb, f, indent, "");
        return sb.toString();
    }

    /** 递归追加字段行及其嵌套子字段 */
    private void appendFieldTree(StringBuilder sb, FieldDef f, String baseIndent, String relIndent) {
        sb.append(baseIndent).append(relIndent).append("- ").append(f.name());
        if (f.type() != null && !f.type().isBlank()) {
            sb.append("(").append(f.type()).append(")");
        }
        if (f.description() != null && !f.description().isBlank()) {
            sb.append(" ").append(f.description());
        }
        if (f.validValues() != null && !f.validValues().isBlank()) {
            sb.append(" [取值范围: ").append(f.validValues()).append("]");
        }
        if (f.children() != null && !f.children().isEmpty()) {
            String childRel = relIndent + "    ";
            for (FieldDef child : f.children()) {
                sb.append("\n");
                appendFieldTree(sb, child, baseIndent, childRel);
            }
        }
    }

    /** 搜索结果条目 */
    private static class ScoredResult {
        final String apiCode;
        final ApiParamSchema schema;
        final int score;
        final boolean whitelisted;
        ScoredResult(String apiCode, ApiParamSchema schema, int score, boolean whitelisted) {
            this.apiCode = apiCode;
            this.schema = schema;
            this.score = score;
            this.whitelisted = whitelisted;
        }
    }

    // ========== 紧凑描述生成 ==========

    /**
     * 构建统一 Tool 的紧凑描述（不枚举接口列表）。
     * <p>
     * 改为固定字符串，引导 LLM 先使用 searchBusinessApi 搜索接口再调用。
     * 工具定义大小 O(1)，不随接口数量增长。
     */
    private String buildUnifiedDescription() {
        return "调用后端业务接口（POST）。通过 apiCode 选择要调用的接口，"
            + "在 params 中传入该接口的业务参数（即报文 body 内容）。\n\n"
            + "**使用流程：不确定 apiCode 时 → 调用 searchBusinessApi 搜索接口 → 调用 getApiDetail 获取该接口的完整参数 → 调用本工具执行调用。**\n\n"
            + "**重要规则：根据问题确定唯一的 apiCode 后只调用一次，不要遍历多个接口。**\n\n"
            + "使用说明:\n"
            + "1. 不确定用哪个 apiCode 时，先调用 searchBusinessApi 搜索\n"
            + "2. 搜索到 apiCode 后，调用 getApiDetail 获取该接口的完整参数信息\n"
            + "3. 根据参数要求，在 params 中传入对应的业务参数\n"
            + "4. 如果不需要参数，可不传 params 或传空对象 {}\n"
            + "5. **调用成功后直接根据返回数据回答用户，禁止继续调用其他接口**";
    }

    // ========== 紧凑入参 JSON Schema 生成 ==========

    /**
     * 构建统一 Tool 的紧凑 JSON Schema（不生成 enum 列表）。
     * <p>
     * 结构：
     * <pre>
     * {
     *   "apiCode": "core1200109445_runService",  // 接口编码
     *   "params": { ... }                        // 业务参数
     * }
     * </pre>
     * apiCode 字段不生成 enum 列表（避免 Schema 过大），
     * 在 description 中引导 LLM 先搜索再调用。
     */
    private String buildUnifiedInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // apiCode 字段——始终紧凑，不生成 enum
        Map<String, Object> apiCodeProp = new LinkedHashMap<>();
        apiCodeProp.put("type", "string");
        apiCodeProp.put("description",
            "接口编码，格式：{业务类名}_{方法名}。请先调用 searchBusinessApi 搜索获取 apiCode，再用 getApiDetail 查看该接口的完整参数。");
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
