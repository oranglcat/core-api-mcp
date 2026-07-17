package com.mcp.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 微服务实例 — 表示注册到 MCP Adapter 的一个后端微服务。
 * <p>
 * 每个实例包含该服务的唯一标识、基础 URL、服务端口以及路由匹配模式。
 * 由 {@link NacosServiceDiscoverer} 从 Nacos 动态发现并填充。
 */
public class ServiceInstance {

    /** 服务唯一标识（如 "ENSEMBLE-RB-xxx"），用于日志和异常追踪 */
    private String id;

    /** 服务基础 URL，如 http://10.127.7.141:9021 */
    private String baseUrl;

    /** 路由匹配模式，支持 Ant 风格通配符，如 ["/rb/**"] */
    private List<String> routePatterns = new ArrayList<>();

    /** 服务端口（从 Nacos 实例获取，用于构造 Comet URL） */
    private int port;

    // ========== getters & setters ==========

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public List<String> getRoutePatterns() { return routePatterns; }
    public void setRoutePatterns(List<String> routePatterns) {
        this.routePatterns = routePatterns != null ? routePatterns : new ArrayList<>();
    }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
