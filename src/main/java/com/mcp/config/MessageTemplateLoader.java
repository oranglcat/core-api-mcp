package com.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报文模板加载器。
 * <p>
 * 从 {@code service.message-format.template-dir} 目录加载所有接口的请求报文模板。
 * 每个模板对应一个接口，文件名由接口 URL 路径归一化得到。
 * <p>
 * 模板内容为完整的请求报文结构（不含 body），body 由 {@link com.mcp.tool.HttpForwarder}
 * 在运行时根据 LLM 传入的参数自动填充。
 */
@Component
public class MessageTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(MessageTemplateLoader.class);

    /** 归一化路径 → 模板 JSON 映射 */
    private final Map<String, Map<String, Object>> templates = new ConcurrentHashMap<>();

    /** 模板根目录 — 用于子目录结构下计算正确的相对路径 key */
    private File templateRootDir;

    private final MessageFormatConfig config;
    private final ObjectMapper objectMapper;

    /** 默认模板文件名（目录级别的兜底） */
    private static final String DEFAULT_TEMPLATE_NAME = "_default";

    public MessageTemplateLoader(MessageFormatConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用启动时扫描模板目录，加载所有 .json 模板文件。
     */
    @PostConstruct
    public void loadAll() {
        if (!config.isEnabled()) {
            log.info("Message format is disabled, skipping template loading");
            return;
        }

        String dirPath = config.getTemplateDir();
        if (dirPath == null || dirPath.isBlank()) {
            log.warn("Template directory not configured (service.message-format.template-dir)");
            return;
        }

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("Template directory does not exist: {}. Attempting to create it.", dir.getAbsolutePath());
            if (dir.mkdirs()) {
                createDefaultTemplate(dir);
            } else {
                log.warn("Cannot create template directory {}. Message templates disabled.", dir.getAbsolutePath());
            }
            return;
        }

        this.templateRootDir = dir;
        int count = loadFromDirectory(dir);
        log.info("Loaded {} message templates from: {}", count, dir.getAbsolutePath());
        if (count == 0) {
            log.warn("No template files found in {}. Create *.json files for each API.", dir.getAbsolutePath());
        }
    }

    /**
     * 递归扫描目录加载模板。
     * 支持两种命名方式：
     *   1. 扁平命名：{normalized_path}.json（如 pf_xxx_pod_params_sync.json）
     *   2. 子目录结构：{path_segments}/{method}.json（如 pf/xxx/pod/params/sync.json）
     */
    private int loadFromDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;

        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += loadFromDirectory(file);
            } else if (file.getName().endsWith(".json")) {
                String name = file.getName();
                // 去掉 .json 后缀（加长度检查防止越界）
                String key = name.length() > 5 ? name.substring(0, name.length() - 5) : "";

                // 计算相对于模板根目录的路径，支持子目录结构
                String relativePath = getRelativePath(this.templateRootDir, file);
                if (relativePath != null) {
                    key = relativePath;
                }

                try {
                    Map<String, Object> template = objectMapper.readValue(file, Map.class);
                    templates.put(key, template);
                    log.debug("  Loaded template: {} ← {}", key, file.getAbsolutePath());
                    count++;
                } catch (IOException e) {
                    log.error("  Failed to load template: {} - {}", file.getAbsolutePath(), e.getMessage());
                }
            }
        }
        return count;
    }

    /** 获取文件相对于模板目录的路径（去掉 .json，恢复路径分隔符） */
    private String getRelativePath(File baseDir, File file) {
        String basePath = baseDir.getAbsolutePath().replace("\\", "/");
        String filePath = file.getAbsolutePath().replace("\\", "/");
        if (!filePath.startsWith(basePath)) return null;

        String relative = filePath.substring(basePath.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.endsWith(".json")) relative = relative.substring(0, relative.length() - 5);
        return relative;
    }

    /**
     * 根据 API URL 路径获取对应的请求报文模板。
     *
     * @param apiPath API 路径（如 /pf/xxx/pod/params/sync）
     * @return 模板 Map（包含 sysHead、appHead 等），无匹配时返回 null
     */
    public Map<String, Object> getTemplate(String apiPath) {
        if (apiPath == null || apiPath.isBlank()) return null;

        String normalized = normalizePath(apiPath);

        // 1. 精确匹配归一化路径
        Map<String, Object> template = templates.get(normalized);
        if (template != null) return template;

        // 2. 尝试子目录结构匹配（将归一化路径的 _ 换回 /）
        String subdirKey = normalized.replace("_", "/");
        template = templates.get(subdirKey);
        if (template != null) return template;

        // 3. 尝试默认模板
        template = templates.get(DEFAULT_TEMPLATE_NAME);
        if (template != null) return template;

        return null;
    }

    /**
     * 归一化 URL 路径为模板 key。
     * /pf/xxx/pod/params/sync → pf_xxx_pod_params_sync
     */
    private String normalizePath(String path) {
        path = path.trim();
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        // 路径分隔符转下划线（合并连续匹配）
        path = path.replaceAll("[/\\\\-]+", "_");
        return path;
    }

    /** 模板目录不存在时，创建一个示例默认模板方便用户参考 */
    private void createDefaultTemplate(File dir) {
        try {
            Map<String, Object> defaultTemplate = new LinkedHashMap<>();

            Map<String, String> sysHead = new LinkedHashMap<>();
            sysHead.put("seqNo", "${seqNo}");
            sysHead.put("subSeqNo", "${subSeqNo}");
            sysHead.put("tranDate", "${tranDate}");
            sysHead.put("tranTimestamp", "${tranTimestamp}");
            sysHead.put("branchId", "${branchId}");
            sysHead.put("userId", "${userId}");
            sysHead.put("userLang", "CHINESE");
            sysHead.put("moduleId", "CL");
            sysHead.put("sceneId", "01");
            sysHead.put("serverId", "127.0.0.1");
            sysHead.put("sourceType", "MT");
            sysHead.put("authFlag", "N");
            defaultTemplate.put("sysHead", sysHead);

            Map<String, String> appHead = new LinkedHashMap<>();
            appHead.put("currentNum", "0");
            appHead.put("pageEnd", "0");
            appHead.put("pageStart", "0");
            appHead.put("pgupOrPgdn", "0");
            appHead.put("totalNum", "-1");
            defaultTemplate.put("appHead", appHead);

            File defaultFile = new File(dir, DEFAULT_TEMPLATE_NAME + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(defaultFile, defaultTemplate);
            log.info("Created default template: {}", defaultFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to create default template: {}", e.getMessage());
        }
    }
}
