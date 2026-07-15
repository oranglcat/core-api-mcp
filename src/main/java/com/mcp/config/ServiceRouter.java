package com.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

/**
 * 服务路由器 — 根据 API 路径匹配目标微服务。
 * <p>
 * 使用 Ant 路径模式匹配，采用<b>最长前缀优先</b>策略：
 * 当多个服务的路由模式同时匹配时，路径段具体字符数最多的模式胜出。
 */
@Component
public class ServiceRouter {

    private static final Logger log = LoggerFactory.getLogger(ServiceRouter.class);

    private final ServiceRegistry registry;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public ServiceRouter(ServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 根据 API 路径匹配目标微服务实例。
     * <p>
     * 路由规则示例：
     * <pre>
     * /pf/inq/xxx/loan/query  → 匹配 /pf/**  → Service-PF
     * /rib/infin/inetest/bind  → 匹配 /rib/**  → Service-RIB
     * </pre>
     *
     * @param apiPath API 路径（如 /pf/inq/xxx/loan/query）
     * @return 匹配的 {@link ServiceInstance}，无匹配时返回 {@code null}
     */
    public ServiceInstance route(String apiPath) {
        if (apiPath == null || apiPath.isBlank()) return null;

        // 确保路径以 / 开头
        String normalized = apiPath.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;

        ServiceInstance bestMatch = null;
        int bestScore = -1;

        for (ServiceInstance service : registry.getServices()) {
            for (String pattern : service.getRoutePatterns()) {
                if (pathMatcher.match(pattern, normalized)) {
                    // 分值 = 去除通配符后的具体路径段字符数，越长越优先
                    int score = pattern.replace("**", "").replace("*", "").length();
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = service;
                    }
                }
            }
        }

        if (bestMatch != null) {
            log.debug("Route [{}] → service [{}]", apiPath, bestMatch.getId());
        } else {
            log.debug("Route [{}] → no match (will use default)", apiPath);
        }
        return bestMatch;
    }
}
