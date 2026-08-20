package com.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.config.*;
import com.mcp.scanner.FieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP 转发器 — 将 MCP Tool 调用转发为 HTTP 请求到原 Spring Boot 服务。
 * <p>
 * 由 {@link DynamicToolRegistrar} 的统一 Tool 调用，根据 Comet 平台提供的接口路径，
 * 封装标准报文格式，转发 POST 请求到后端业务服务。
 */
@Component
public class HttpForwarder {

    private static final Logger log = LoggerFactory.getLogger(HttpForwarder.class);

    private final RestTemplate restTemplate;
    private final AppConfig config;
    private final MessageFormatConfig messageFormatConfig;
    private final MessageTemplateLoader messageTemplateLoader;
    private final ServiceRouter serviceRouter;
    private final ObjectMapper objectMapper;

    public HttpForwarder(RestTemplate restTemplate, AppConfig config,
                         MessageFormatConfig messageFormatConfig,
                         MessageTemplateLoader messageTemplateLoader,
                         ServiceRouter serviceRouter,
                         ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.messageFormatConfig = messageFormatConfig;
        this.messageTemplateLoader = messageTemplateLoader;
        this.serviceRouter = serviceRouter;
        this.objectMapper = objectMapper;
    }

    /**
     * 统一 POST 转发入口。
     * <p>
     * 由 {@link DynamicToolRegistrar} 调用，根据 Comet Schema 中的 API 路径
     * 拼接完整 URL，封装标准报文后发送 POST 请求到后端服务。
     *
     * @param apiPath     API 路径（如 /pf/inq/xxx/loan/query）
     * @param params      业务参数（即报文 body 内容）
     * @param inputFields 接口入参字段定义列表（含必输标记与取值范围），可为 null
     * @param apiCode     接口编码（如 core12000500_run_service），用于推导 messageType/messageCode
     * @return 格式化后的响应文本
     */
    public String forwardPost(String apiPath, Map<String, Object> params,
                              List<FieldDef> inputFields, String apiCode) {
        // 必输字段校验（含数组/嵌套结构的子字段，递归）
        if (inputFields != null) {
            String requiredError = validateRequiredFields(params, inputFields);
            if (requiredError != null) return requiredError;
        }

        // 取值范围校验（含数组/嵌套结构的子字段，递归）
        if (inputFields != null && params != null) {
            String rangeError = validateValueRanges(params, inputFields);
            if (rangeError != null) return rangeError;
        }

        if (params == null) params = Map.of();

        // 动态路由：根据 API 路径匹配目标微服务，无匹配时使用默认 URL
        ServiceInstance service = serviceRouter.route(apiPath);
        String baseUrl = (service != null) ? service.getBaseUrl() : config.originalUrl();
        String url = baseUrl + apiPath;
        log.info("▶ 路由目标: service={}, url={}",
            service != null ? service.getId() : "default(original)", url);

        try {
            // 标准报文封装（如已启用）
            Object requestPayload;
            if (messageFormatConfig.isEnabled()) {
                requestPayload = buildStandardMessage(apiPath, params, apiCode);
            } else {
                requestPayload = params.isEmpty() ? null : params;
            }

            // 完整请求报文仅 debug 输出——toJsonString 只为日志服务，在 debug 关闭时不再序列化，
            // 避免每次调用都全量序列化并写入日志（CPU + 磁盘开销）
            if (log.isDebugEnabled()) {
                log.debug("▶ 请求报文 [{}]: {}", apiPath, toJsonString(requestPayload));
            }

            String response = restTemplate.postForObject(url, requestPayload, String.class);

            // 响应报文以紧凑 JSON 返回给 LLM（紧凑格式相比 pretty 格式可省约 30-50% token），
            // 完整报文仅 debug 时以 pretty 格式写日志
            String compactResponse = compactJson(response);
            if (log.isDebugEnabled()) {
                log.debug("▶ 响应报文 [{}]:\n{}", apiPath, prettyPrintJson(response));
            }
            log.info("▶ 调用完成 [{}]: 响应 {} 字符", apiPath, compactResponse.length());

            return compactResponse + "\n\n---\n接口调用成功。以上是完整返回数据，请直接回答用户，**不要继续调用其他接口**。";
        } catch (Exception e) {
            log.error("Unified forward failed: POST {} - {}", url, e.getMessage(), e);
            return "调用接口 " + apiPath + " 失败: " + resolveUserMessage(e);
        }
    }

    /**
     * 将异常转换为对 LLM 友好的中文消息，隐藏内部实现细节。
     */
    private String resolveUserMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "服务暂时不可用，请稍后重试。";
        if (msg.contains("Connection refused") || msg.contains("connect timed out")) {
            return "后端服务连接失败（超时或拒绝连接），请确认后端服务已启动。";
        }
        if (msg.contains("400")) return "请求参数格式错误，请检查参数后重试。";
        if (msg.contains("401")) return "认证失败，请联系管理员。";
        if (msg.contains("403")) return "无权限访问，请联系管理员。";
        if (msg.contains("404")) return "请求的接口不存在，请联系管理员。";
        if (msg.contains("500")) return "服务端内部错误，已记录日志，请稍后重试。";
        return "请求处理失败: " + msg;
    }

    // ========== 必输字段校验 ==========

    /**
     * 校验必输字段是否齐全（含数组/嵌套结构的子字段，递归）。
     * <p>
     * 对每一层：缺失的必输字段一次性收集并提示；本层无缺失时递归检查已提供字段的嵌套子字段。
     * 错误信息中的参数名带完整路径（如 rateArray[0].ccy），便于定位。
     *
     * @param params      业务参数（可为 null，视为空参数）
     * @param inputFields 入参字段定义列表
     * @return 缺失必输参数的错误提示，全部齐全时返回 null
     */
    private String validateRequiredFields(Map<String, Object> params, List<FieldDef> inputFields) {
        return checkRequired(params != null ? params : Map.of(), inputFields, "");
    }

    /** 递归检查一层字段集合（顶层或任意嵌套层）的必输完整性 */
    private String checkRequired(Map<String, Object> params, List<FieldDef> fields, String pathPrefix) {
        if (fields == null || fields.isEmpty()) return null;

        // 1) 收集本层缺失的必输字段
        List<String> missing = new ArrayList<>();
        for (FieldDef field : fields) {
            if (!field.required()) continue;
            if (!params.containsKey(field.name()) || params.get(field.name()) == null) {
                missing.add(pathPrefix + field.name());
            }
        }
        if (!missing.isEmpty()) {
            return "错误: 缺少必输参数 [" + String.join(", ", missing) + "]。请提供这些参数后重试。";
        }

        // 2) 递归检查已提供字段的嵌套子字段
        for (FieldDef field : fields) {
            if (field.children() == null || field.children().isEmpty()) continue;
            if (!params.containsKey(field.name()) || params.get(field.name()) == null) continue;

            Object value = params.get(field.name());
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childParams = (Map<String, Object>) map;
                String err = checkRequired(childParams, field.children(), pathPrefix + field.name() + ".");
                if (err != null) return err;
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> childParams = (Map<String, Object>) map;
                        String err = checkRequired(childParams, field.children(), pathPrefix + field.name() + "[" + i + "].");
                        if (err != null) return err;
                    }
                    // 标量元素无子字段，跳过
                }
            }
        }
        return null;
    }

    // ========== 取值范围校验 ==========

    /**
     * 校验参数值是否在字段允许的取值范围内（含数组/嵌套结构的子字段，递归）。
     * <p>
     * 取值范围格式为逗号分隔枚举（如 "D,I"、"01,02"）。
     * 校验规则：
     * <ul>
     *   <li>标量值直接匹配字段自身的取值范围</li>
     *   <li>List 值：元素为标量时逐元素匹配字段自身枚举；元素为对象时按子字段递归校验</li>
     *   <li>Map/对象值：按子字段递归校验</li>
     * </ul>
     *
     * @param params      业务参数
     * @param inputFields 入参字段定义列表
     * @return 校验失败的错误提示，全部通过时返回 null
     */
    private String validateValueRanges(Map<String, Object> params, List<FieldDef> inputFields) {
        return validateFieldList(params, inputFields, "");
    }

    /** 递归校验一层字段集合（顶层或任意嵌套层）的取值 */
    private String validateFieldList(Map<String, Object> params, List<FieldDef> fields, String pathPrefix) {
        if (params == null || fields == null || fields.isEmpty()) return null;
        for (FieldDef field : fields) {
            if (!params.containsKey(field.name()) || params.get(field.name()) == null) continue;
            String path = pathPrefix + field.name();
            String err = validateField(field, params.get(field.name()), path);
            if (err != null) return err;
        }
        return null;
    }

    /**
     * 校验单个字段的值：先校验字段自身取值范围，再递归校验嵌套子字段。
     *
     * @param field 字段定义
     * @param value 参数值
     * @param path  字段完整路径（如 rateArray[0].ccy），用于错误提示定位
     */
    private String validateField(FieldDef field, Object value, String path) {
        // 1) 字段自身的取值范围
        String valid = field.validValues();
        if (valid != null && !valid.isBlank()) {
            Set<String> allowed = parseValidValues(valid);
            if (!allowed.isEmpty()) {
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item == null || item instanceof Map) continue;  // 对象元素不参与自身枚举
                        if (!allowed.contains(String.valueOf(item).trim())) {
                            return buildRangeError(path, valid, allowed, String.valueOf(item));
                        }
                    }
                } else if (!(value instanceof Map)) {
                    String s = String.valueOf(value).trim();
                    if (!allowed.contains(s)) {
                        return buildRangeError(path, valid, allowed, s);
                    }
                }
                // Map 值对字段自身的枚举不适用（对象类型字段一般不配 scope）
            }
        }

        // 2) 递归校验嵌套子字段
        if (field.children() != null && !field.children().isEmpty()) {
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childParams = (Map<String, Object>) map;
                return validateFieldList(childParams, field.children(), path + ".");
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> childParams = (Map<String, Object>) map;
                        String err = validateFieldList(childParams, field.children(), path + "[" + i + "].");
                        if (err != null) return err;
                    }
                    // 标量元素不参与子字段递归（无法按字段名映射）
                }
            }
        }
        return null;
    }

    /** 构造取值范围校验失败的错误提示 */
    private String buildRangeError(String path, String valid, Set<String> allowed, String actualValue) {
        return "错误: 参数 [" + path + "] 的值 '" + actualValue
            + "' 不在允许范围 [" + valid + "] 内，请使用: " + String.join(", ", allowed) + "。";
    }

    /** 解析逗号分隔的取值范围（支持半角/全角逗号），按原顺序去重返回 */
    private Set<String> parseValidValues(String valid) {
        return Arrays.stream(valid.split("[,，]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ========== 标准报文格式封装 ==========

    /**
     * 将业务参数封装为标准金融报文格式。
     * <p>
     * 策略（按优先级）：
     * <ol>
     *   <li>从 {@link MessageTemplateLoader} 查找 per-API 模板 → 加载并解析占位符</li>
     *   <li>兜底：使用全局配置 {@code service.message-format.sys-head/app-head}</li>
     * </ol>
     * body 始终由 LLM 传入的参数动态填充。
     *
     * @param apiPath      API 路径，用于匹配 per-API 模板
     * @param businessBody LLM 传入的业务参数
     */
    /** 营业日期缓存（时间过期，线程安全）。runDate 每天只变一次，缓存 5 分钟即可。 */
    private volatile String cachedRunDate;
    private volatile long cachedRunDateExpiry;

    /** 缓存有效期（毫秒），默认 5 分钟 */
    private static final long RUN_DATE_CACHE_TTL = 5 * 60 * 1000;

    /**
     * 调用 ENSEMBLE-OB-SERVICE 接口查询营业日期（使用标准报文格式）。
     * <p>
     * 注意：此处使用标准报文格式包装请求，因为 OB 服务与其他核心服务一样
     * 要求标准金融报文格式（sysHead + appHead + body），不支持裸 JSON。
     * <p>
     * 请求体结构：
     * <pre>
     * POST /ob/inq/system/branch/user
     * {
     *   "sysHead": { "tranDate":"...", "branchId":"...", "userId":"..." },
     *   "body":    { "queryInd": "fm_system" }
     * }
     * </pre>
     * 响应中的 runDate 字段通常在 sysHead 中返回。
     */
    private String fetchRunDate() {
        long now = System.currentTimeMillis();

        // 时间过期缓存（线程安全：volatile 保证可见性，runDate 每天一致无需加锁）
        if (cachedRunDate != null && now < cachedRunDateExpiry) {
            return cachedRunDate;
        }

        // 通过路由器获取 OB 服务地址
        ServiceInstance obService = serviceRouter.route("/ob/inq/system/branch/user");
        String baseUrl = (obService != null) ? obService.getBaseUrl() : config.originalUrl();
        String url = baseUrl + "/ob/inq/system/branch/user";

        try {
            log.info("查询营业日期: POST {} (OB服务路由结果: {})", url,
                obService != null ? obService.getId() : "null→使用默认URL");

            // ---- 构造标准报文格式请求 ----
            // 注意：此处我们正在查询 runDate，所以 sysHead 中的 tranDate 使用当前系统日期
            //（调用 OB 服务拿 runDate 本身就是获取正确的营业日期，sysHead 中的日期是用于
            //   OB 服务自身处理请求的，不影响返回的 runDate 值）
            LocalDateTime nowDt = LocalDateTime.now();
            Map<String, Object> sysHead = new LinkedHashMap<>(messageFormatConfig.getSysHead());
            sysHead.put("seqNo", generateSeqNo(nowDt));
            sysHead.put("subSeqNo", generateSubSeqNo(nowDt));
            sysHead.put("tranDate", nowDt.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            sysHead.put("tranTimestamp", nowDt.format(DateTimeFormatter.ofPattern("HHmmssSSS")));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("sysHead", sysHead);
            requestBody.put("body", Map.of("queryInd", "fm_system"));

            String response = restTemplate.postForObject(url, requestBody, String.class);
            log.debug("营业日期原始响应: {}", response != null ? response.substring(0, Math.min(response.length(), 500)) : "null");
            if (response != null) {
                Object root = objectMapper.readValue(response, Object.class);
                // 在响应中递归查找 runDate 字段（可能在 sysHead/body 嵌套结构中）
                String runDate = findStringField(root, "runDate");
                if (runDate != null && !runDate.isEmpty()) {
                    // 格式统一为 yyyyMMdd
                    String formatted = runDate.replace("-", "").substring(0, 8);
                    cachedRunDate = formatted;
                    cachedRunDateExpiry = now + RUN_DATE_CACHE_TTL;
                    log.info("查询营业日期成功: {} -> {} (缓存 {} 分钟)", runDate, formatted,
                        RUN_DATE_CACHE_TTL / 60_000);
                    return formatted;
                }
                log.warn("响应中未找到 runDate 字段。响应结构: {}",
                    truncateJson(root, 300));
            }
        } catch (Exception e) {
            log.error("查询营业日期失败 (url={}): {}", url, e.getMessage(), e);
        }
        String fallback = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        log.warn("回退系统日期: {} (营业日期查询失败)", fallback);
        // 回退：使用系统日期
        return fallback;
    }

    /**
     * 在嵌套 JSON 对象中递归查找指定字段的值（支持 String / Number / 嵌套结构）。
     */
    @SuppressWarnings("unchecked")
    private String findStringField(Object node, String fieldName) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (fieldName.equals(entry.getKey())) {
                    Object val = entry.getValue();
                    if (val instanceof String) {
                        return (String) val;
                    } else if (val instanceof Number) {
                        // OB 服务可能将 runDate 返回为数值（20240526而非"20240526"）
                        return String.valueOf(val);
                    }
                    // 非 String/Number 类型继续递归（可能在嵌套 Map/List 中）
                }
                String found = findStringField(entry.getValue(), fieldName);
                if (found != null) return found;
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                String found = findStringField(item, fieldName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Map<String, Object> buildStandardMessage(String apiPath, Map<String, Object> businessBody,
                                                       String apiCode) {
        // 预计算动态字段—使用同一时间点确保一致性
        LocalDateTime now = LocalDateTime.now();
        String runDate = fetchRunDate();

        // ---- 占位符池 ----
        // 1a. 从配置加载的系统头默认值（仅用作模板 ${...} 占位符解析）
        Map<String, String> resolvedFields = new LinkedHashMap<>(messageFormatConfig.getSysHead());
        // 1b. 动态生成字段（seqNo / subSeqNo / tranTimestamp ...）
        resolvedFields.put("seqNo", generateSeqNo(now));
        resolvedFields.put("subSeqNo", generateSubSeqNo(now));
        resolvedFields.put("tranDate", runDate);
        resolvedFields.put("tranTimestamp", now.format(DateTimeFormatter.ofPattern("HHmmssSSS")));
        resolvedFields.put("serverId", "127.0.0.1");
        // 补充模板中可能出现的公共占位符
        resolvedFields.putIfAbsent("serviceCode", "");
        resolvedFields.putIfAbsent("messageType", "");
        resolvedFields.putIfAbsent("messageCode", "");

        // 1c. 从 core 类接口的 apiCode 中动态推导 messageType / messageCode
        if (apiCode != null) {
            String[] extracted = extractMessageTypeAndCode(apiCode);
            if (extracted[0] != null) {
                resolvedFields.put("messageType", extracted[0]);
                resolvedFields.put("messageCode", extracted[1]);
            }
        }

        // ---- 优先使用 per-API 模板 ----
        Map<String, Object> template = messageTemplateLoader.getTemplate(apiPath);
        if (template != null) {
            Map<String, Object> message = deepResolvePlaceholders(template, resolvedFields);
            // 确保 tranDate 在 sysHead 中（模板中存在 ${tranDate} 时自动解析，
            // 此处作为兜底保证即使模板未定义 ${tranDate} 也能正确设置）
            ensureFieldInSysHead(message, "tranDate", runDate);
            message.put("body", businessBody.isEmpty() ? new LinkedHashMap<>() : businessBody);
            log.debug("Using per-API template for: {}", apiPath);
            return message;
        }

        // ---- 兜底：全局配置模板 ----
        log.debug("No per-API template for: {}, using global config fallback", apiPath);
        Map<String, Object> message = new LinkedHashMap<>();
        Map<String, String> sysHead = new LinkedHashMap<>(messageFormatConfig.getSysHead());
        // 显式设置每个动态字段到 sysHead（不通过 putAll(dynamicFields) 混入）
        sysHead.put("tranDate", runDate);
        sysHead.put("seqNo", generateSeqNo(now));
        sysHead.put("subSeqNo", generateSubSeqNo(now));
        sysHead.put("tranTimestamp", now.format(DateTimeFormatter.ofPattern("HHmmssSSS")));
        sysHead.put("serverId", "127.0.0.1");
        message.put("sysHead", sysHead);
        message.put("appHead", new LinkedHashMap<>(messageFormatConfig.getAppHead()));
        message.put("body", businessBody.isEmpty() ? new LinkedHashMap<>() : businessBody);
        return message;
    }

    /**
     * 从 core 类接口的 apiCode 中提取 messageType 和 messageCode。
     * <p>
     * core 类接口的 apiCode 格式为 {@code core + 8位数字 + _ + 方法名}，
     * 其中 8 位数字按银行业务规范定义：前 4 位为 messageType，后 4 位为 messageCode。
     * <p>
     * 示例：
     * <pre>
     *   "core12000500_run_service" → messageType = "1200", messageCode = "0500"
     *   "core12001094_runService"  → messageType = "1200", messageCode = "1094"
     * </pre>
     * <p>
     * 非 core 类接口（apiCode 不以 "core" 开头或不含 8 位数字）返回 {@code {null, null}}，
     * 调用方应忽略该结果，保持原有默认值。
     *
     * @param apiCode 接口编码
     * @return 长度为 2 的数组：[messageType, messageCode]，匹配失败时两个元素均为 null
     */
    private String[] extractMessageTypeAndCode(String apiCode) {
        if (apiCode == null || apiCode.isEmpty()) {
            return new String[]{null, null};
        }
        // 匹配 "core" 后紧跟的 8 位数字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "core(\\d{4})(\\d{4})", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(apiCode);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return new String[]{null, null};
    }

    /**
     * 确保指定字段在消息的 sysHead 中存在且已解析。
     * <p>
     * 覆盖策略：
     * <ol>
     *   <li>字段不存在 → 补入</li>
     *   <li>字段值为 null → 补入</li>
     *   <li>字段值仍为未解析的占位符（如 {@code "${tranDate}"}）→ 覆盖为真实值</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private void ensureFieldInSysHead(Map<String, Object> message, String fieldName, String value) {
        if (message.containsKey("sysHead") && message.get("sysHead") instanceof Map) {
            Map<String, Object> sysHead = (Map<String, Object>) message.get("sysHead");
            Object existing = sysHead.get(fieldName);
            if (existing == null
                || (existing instanceof String && ((String) existing).contains("${"))) {
                sysHead.put(fieldName, value);
            }
        }
    }

    /**
     * 递归解析模板中的占位符。
     * <p>
     * 模板文件中可包含 ${seqNo}、${tranDate} 等占位符，运行时替换为真实值。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepResolvePlaceholders(Map<String, Object> template,
                                                         Map<String, String> dynamicFields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(entry.getKey(), resolvePlaceholderString((String) value, dynamicFields));
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                result.put(entry.getKey(), deepResolvePlaceholders(nested, dynamicFields));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /** 替换字符串中的 ${...} 占位符 */
    private String resolvePlaceholderString(String value, Map<String, String> dynamicFields) {
        if (value == null || !value.contains("${")) return value;
        String result = value;
        for (Map.Entry<String, String> entry : dynamicFields.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        // 检查仍未解析的占位符
        if (result.contains("${")) {
            log.warn("未解析的模板占位符: {} (原始: {})", result.replaceAll(".*(\\$\\{[^}]+\\}).*", "$1"), value);
        }
        return result;
    }

    /** 生成全局唯一流水号 seqNo */
    private String generateSeqNo(LocalDateTime now) {
        return "SY" + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmssSSS"))
            + String.format("%06d", (int) (Math.random() * 1_000_000));
    }

    /** 生成子流水号 subSeqNo */
    private String generateSubSeqNo(LocalDateTime now) {
        return "SS" + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmssSSS"))
            + String.format("%06d", (int) (Math.random() * 1_000_000));
    }

    // ========== 工具方法 ==========

    /** 将对象序列化为 JSON 字符串（用于日志输出） */
    private String toJsonString(Object obj) {
        if (obj == null) return "null";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /** 美化 JSON 输出 */
    private String prettyPrintJson(String raw) {
        try {
            Object json = objectMapper.readValue(raw, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            return raw;  // 不是 JSON 就原样返回
        }
    }

    /**
     * 将 JSON 字符串紧凑化（去除空白/缩进），解析失败时原样返回。
     * <p>
     * 紧凑 JSON 相比 pretty 格式可减少 30-50% 的 token 占用，LLM 可正常解析。
     */
    private String compactJson(String raw) {
        if (raw == null) return "null";
        try {
            Object json = objectMapper.readValue(raw, Object.class);
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            return raw;  // 不是 JSON 就原样返回
        }
    }

    /**
     * 将 JSON 对象截断为指定长度的纯文本摘要（用于日志输出，避免打爆日志）。
     */
    private String truncateJson(Object obj, int maxLen) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            if (json.length() <= maxLen) return json;
            return json.substring(0, maxLen) + "... (truncated " + json.length() + " chars)";
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
