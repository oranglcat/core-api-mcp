package com.mcp.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 微服务实例 — 表示注册到 MCP Adapter 的一个后端微服务。
 * <p>
 * 每个实例包含该服务的唯一标识、基础 URL、Comet 平台地址、以及路由匹配模式。
 */
public class ServiceInstance {

    /** 服务唯一标识（如 "pf", "rib"），用于日志和异常追踪 */
    private String id;

    /** 服务基础 URL，如 http://10.127.7.141:9021 */
    private String baseUrl;

    /** 路由匹配模式，支持 Ant 风格通配符，如 ["/pf/**", "/pf/inq/**"] */
    private List<String> routePatterns = new ArrayList<>();

    /** 该服务对应的 Comet 平台配置（地址端口） */
    private Comet comet = new Comet();

    // ========== getters & setters ==========

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public List<String> getRoutePatterns() { return routePatterns; }
    public void setRoutePatterns(List<String> routePatterns) {
        this.routePatterns = routePatterns != null ? routePatterns : new ArrayList<>();
    }

    public Comet getComet() { return comet; }
    public void setComet(Comet comet) {
        this.comet = comet != null ? comet : new Comet();
    }

    /**
     * 获取该服务的完整 Comet URL（http://host:port）
     */
    public String getCometUrl() {
        return "http://" + comet.host + ":" + comet.port;
    }

    /**
     * Comet 平台连接配置。
     * 同一 IP 下不同微服务可能有不同的 Comet 端口。
     */
    public static class Comet {
        private String host = "127.0.0.1";
        private int port = 9020;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }
}
