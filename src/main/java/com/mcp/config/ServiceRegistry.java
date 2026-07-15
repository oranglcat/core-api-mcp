package com.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 微服务注册表 — 绑定 {@code application.yml} 中 {@code service.registry.*} 的配置项。
 * <p>
 * 管理所有后端微服务的连接信息和路由规则，支持多服务集群模式。
 * 同时保持对旧版 {@code service.original.url} 单服务模式的兼容。
 */
@Configuration
@ConfigurationProperties(prefix = "service.registry")
public class ServiceRegistry {

    /** 微服务实例列表 */
    private List<ServiceInstance> services = new ArrayList<>();

    public List<ServiceInstance> getServices() { return services; }
    public void setServices(List<ServiceInstance> services) {
        this.services = services != null ? services : new ArrayList<>();
    }

    /** 是否已配置至少一个微服务实例 */
    public boolean hasServices() {
        return services != null && !services.isEmpty();
    }
}
