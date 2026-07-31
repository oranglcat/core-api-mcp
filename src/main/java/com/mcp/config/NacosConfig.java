package com.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Nacos 服务发现配置 — 绑定 application.yml 中 service.nacos.* 的配置项。
 */
@Configuration
@ConfigurationProperties(prefix = "service.nacos")
public class NacosConfig {

    /** 是否启用 Nacos 服务发现 */
    private boolean enabled = false;

    /** Nacos 服务器地址 */
    private String serverAddr;

    /** 命名空间 ID */
    private String namespace;

    /** 分组名称 */
    private String group = "DEFAULT_GROUP";

    /** 定时刷新间隔（秒），0=不刷新 */
    private int refreshInterval = 30;

    /** 服务过滤配置 */
    private FilterConfig filter = new FilterConfig();

    // ===== getters & setters =====

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public int getRefreshInterval() { return refreshInterval; }
    public void setRefreshInterval(int refreshInterval) { this.refreshInterval = refreshInterval; }

    public FilterConfig getFilter() { return filter; }
    public void setFilter(FilterConfig filter) { this.filter = filter != null ? filter : new FilterConfig(); }

    /**
     * 服务过滤配置 — 基于 AntPath 模式匹配服务名。
     */
    public static class FilterConfig {
        /** 是否启用过滤 */
        private boolean enabled = false;
        /** 包含模式列表（空=全部包含） */
        private List<String> includePatterns = List.of();
        /** 排除模式列表 */
        private List<String> excludePatterns = List.of();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getIncludePatterns() { return includePatterns; }
        public void setIncludePatterns(List<String> includePatterns) { this.includePatterns = includePatterns != null ? includePatterns : List.of(); }
        public List<String> getExcludePatterns() { return excludePatterns; }
        public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns != null ? excludePatterns : List.of(); }
    }
}
