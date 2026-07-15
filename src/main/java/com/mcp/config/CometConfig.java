package com.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Comet 接口文档平台配置 — 绑定 application.yml 中 service.comet.* 的配置项。
 * <p>
 * Comet 是一个内部接口文档管理系统，提供 REST API 查询接口列表和入参/出参定义。
 */
@Configuration
@ConfigurationProperties(prefix = "service.comet")
public class CometConfig {

    /** Comet 平台的基础 URL（如 http://10.127.7.141:9020） */
    private String baseUrl = "";

    /** 请求超时时间（毫秒） */
    private int timeout = 5000;

    /** 是否启用 Comet 参数 Schema 加载 */
    private boolean enabled = true;

    /** 认证/请求头配置 */
    private Auth auth = new Auth();

    public String getBaseUrl() { return baseUrl; }
    public int getTimeout() { return timeout; }
    public boolean isEnabled() { return enabled; }
    public Auth getAuth() { return auth; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public static class Auth {
        private boolean enabled = false;
        private String token;

        public boolean isEnabled() { return enabled; }
        public String getToken() { return token; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setToken(String token) { this.token = token; }
    }

    /** 获取通用请求头 */
    public java.util.Map<String, String> commonHeaders() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        if (auth != null && auth.isEnabled() && auth.token != null && !auth.token.isBlank()) {
            headers.put("Authorization", "Bearer " + auth.token);
        }
        return headers;
    }
}
