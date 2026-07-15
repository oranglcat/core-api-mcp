package com.mcp.tool;

import com.mcp.config.AppConfig;
import com.mcp.config.MessageFormatConfig;
import com.mcp.config.MessageTemplateLoader;
import com.mcp.config.ServiceInstance;
import com.mcp.config.ServiceRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * @param apiPath      API 路径（如 /pf/inq/xxx/loan/query）
     * @param params       业务参数（即报文 body 内容）
     * @param requiredFields 必输字段列表（未提供时返回友好提示），可为 null
     * @return 格式化后的响应文本
     */
    public String forwardPost(String apiPath, Map<String, Object> params,
                              java.util.List<String> requiredFields) {
        // 必输字段校验
        if (requiredFields != null && !requiredFields.isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (String field : requiredFields) {
                if (params == null || !params.containsKey(field) || params.get(field) == null) {
                    missing.add(field);
                }
            }
            if (!missing.isEmpty()) {
                return "错误: 缺少必输参数 [" + String.join(", ", missing) + "]。请提供这些参数后重试。";
            }
        }

        if (params == null) params = Map.of();

        // 动态路由：根据 API 路径匹配目标微服务，无匹配时使用默认 URL
        ServiceInstance service = serviceRouter.route(apiPath);
        String baseUrl = (service != null) ? service.getBaseUrl() : config.originalUrl();
        String url = baseUrl + apiPath;
        log.info("Unified forward: POST {} (route: {}, params: {})",
            url, service != null ? service.getId() : "default", params.keySet());

        try {
            // 标准报文封装（如已启用）
            Object requestPayload;
            if (messageFormatConfig.isEnabled()) {
                requestPayload = buildStandardMessage(apiPath, params);
            } else {
                requestPayload = params.isEmpty() ? null : params;
            }

            String response = restTemplate.postForObject(url, requestPayload, String.class);
            String prettyJson = prettyPrintJson(response);
            // 成功响应末尾追加止语引导，防止 LLM 继续调用其他接口进行"探索"
            return prettyJson + "\n\n---\n接口调用成功。以上是完整返回数据，请直接回答用户，**不要继续调用其他接口**。";
        } catch (Exception e) {
            log.error("Unified forward failed: POST {} - {}", url, e.getMessage(), e);
            return "调用接口 " + apiPath + " 失败: " + resolveUserMessage(e);
        }
    }

    /**
     * 兼容无 requiredFields 参数的重载（保持向后兼容）
     */
    public String forwardPost(String apiPath, Map<String, Object> params) {
        return forwardPost(apiPath, params, null);
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
    private Map<String, Object> buildStandardMessage(String apiPath, Map<String, Object> businessBody) {
        // 预计算动态字段—使用同一时间点确保一致性
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> dynamicFields = new LinkedHashMap<>(messageFormatConfig.getSysHead());
        dynamicFields.put("seqNo", generateSeqNo(now));
        dynamicFields.put("subSeqNo", generateSubSeqNo(now));
        dynamicFields.put("tranDate", now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        dynamicFields.put("tranTimestamp", now.format(DateTimeFormatter.ofPattern("HHmmssSSS")));
        dynamicFields.put("serverId", "127.0.0.1");
        // 补充模板中可能出现的公共占位符
        dynamicFields.putIfAbsent("serviceCode", "");
        dynamicFields.putIfAbsent("messageType", "");
        dynamicFields.putIfAbsent("messageCode", "");

        // 1. 优先使用 per-API 模板
        Map<String, Object> template = messageTemplateLoader.getTemplate(apiPath);
        if (template != null) {
            Map<String, Object> message = deepResolvePlaceholders(template, dynamicFields);
            message.put("body", businessBody.isEmpty() ? new LinkedHashMap<>() : businessBody);
            log.debug("Using per-API template for: {}", apiPath);
            return message;
        }

        // 2. 兜底：全局配置模板
        log.debug("No per-API template for: {}, using global config fallback", apiPath);
        Map<String, Object> message = new LinkedHashMap<>();
        Map<String, String> sysHead = new LinkedHashMap<>(messageFormatConfig.getSysHead());
        sysHead.putAll(dynamicFields);   // 动态字段覆盖配置中的同名占位
        message.put("sysHead", sysHead);
        message.put("appHead", new LinkedHashMap<>(messageFormatConfig.getAppHead()));
        message.put("body", businessBody.isEmpty() ? new LinkedHashMap<>() : businessBody);
        return message;
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

    /** 美化 JSON 输出 */
    private String prettyPrintJson(String raw) {
        try {
            Object json = objectMapper.readValue(raw, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            return raw;  // 不是 JSON 就原样返回
        }
    }
}
