package com.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准报文格式配置。
 * <p>
 * 金融类系统采用固定的 JSON 报文格式，请求/响应分为 4 大顶层节点：
 * <ul>
 *   <li><b>sysHead</b> — 系统公共头（服务编码、交易码、流水号等）</li>
 *   <li><b>appHead</b> — 应用业务头（分页信息）</li>
 *   <li><b>localHead</b> — 本地渠道响应头（仅响应报文中存在）</li>
 *   <li><b>body</b> — 业务数据体（随接口变化）</li>
 * </ul>
 * <p>
 * 启用后，MCP Adapter 自动将 LLM 传入的业务参数封装为标准报文再转发给后端。
 */
@Configuration
@ConfigurationProperties(prefix = "service.message-format")
public class MessageFormatConfig {

    /** 是否启用标准报文格式封装 */
    private boolean enabled = false;

    /**
     * 报文模板目录。
     * <p>
     * 每个接口的请求模板以 JSON 文件形式存放，文件名为 URL 路径归一化后（/ → _）加上 .json。
     * 例如：/pf/xxx/pod/params/sync → pf_xxx_pod_params_sync.json
     * <p>
     * 模板文件包含 sysHead 和 appHead，body 由适配器根据 LLM 参数自动填充。
     * 目录下也可放 _default.json 作为默认模板兜底。
     */
    private String templateDir = "./templates";

    /** sysHead 模板字段（从配置读取，仅当无 per-API 模板时作为兜底） */
    private Map<String, String> sysHead = new LinkedHashMap<>();

    /** appHead 模板字段（同上，兜底用） */
    private Map<String, String> appHead = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public String getTemplateDir() { return templateDir; }
    public Map<String, String> getSysHead() { return Collections.unmodifiableMap(sysHead); }
    public Map<String, String> getAppHead() { return Collections.unmodifiableMap(appHead); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setTemplateDir(String templateDir) { this.templateDir = templateDir; }
    public void setSysHead(Map<String, String> sysHead) { this.sysHead = sysHead; }
    public void setAppHead(Map<String, String> appHead) { this.appHead = appHead; }
}
