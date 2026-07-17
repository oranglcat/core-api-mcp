package com.mcp.config;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 微服务注册表 — 管理所有后端微服务的连接信息和路由规则。
 * <p>
 * 由 {@link NacosServiceDiscoverer} 从 Nacos 动态发现并填充，
 * {@link ServiceRouter} 和 {@link com.mcp.scanner.CometApiSchemaLoader} 读取使用。
 * <p>
 * 线程安全：{@link #services} 声明为 {@code volatile}，确保调度线程（Nacos 定时刷新）
 * 的更新对读线程可见；内部使用不可变列表避免外部并发修改。
 */
@Component
public class ServiceRegistry {

    /** 微服务实例列表（volatile 保证跨线程可见性） */
    private volatile List<ServiceInstance> services = List.of();

    public List<ServiceInstance> getServices() { return services; }
    public void setServices(List<ServiceInstance> services) {
        this.services = services != null ? List.copyOf(services) : List.of();
    }

    /** 是否已配置至少一个微服务实例 */
    public boolean hasServices() {
        return !services.isEmpty();
    }
}
