package com.mcp.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Nacos 服务发现核心类。
 * <p>
 * 启动时连接 Nacos 查询服务列表，按 filter 规则过滤，查询健康实例，
 * 构建 ServiceInstance 并注入 ServiceRegistry。
 * 支持定时刷新（通过 refresh-interval 配置）。
 */
@Component
public class NacosServiceDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(NacosServiceDiscoverer.class);

    private final NacosConfig nacosConfig;
    private final ServiceRegistry serviceRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nacos-refresher");
        t.setDaemon(true);
        return t;
    });

    /**
     * 复用的 NamingService 实例（首次刷新时创建，后续刷新复用）。
     * <p>
     * 早期实现每次刷新都新建一个 Nacos NamingService 且从不关闭——每个实例会
     * 拉起多条 gRPC/调度后台线程，长时间运行线程数无限累积，最终拖垮 CPU 与内存。
     * 这里改为单例复用 + 应用退出时 {@link #shutdown()} 关闭。
     */
    private volatile NamingService naming;

    public NacosServiceDiscoverer(NacosConfig nacosConfig, ServiceRegistry serviceRegistry) {
        this.nacosConfig = nacosConfig;
        this.serviceRegistry = serviceRegistry;
    }

    @PostConstruct
    public void init() {
        if (!nacosConfig.isEnabled()) {
            log.info("Nacos 服务发现未启用（service.nacos.enabled=false），跳过");
            return;
        }

        log.info("Nacos 服务发现已启用，正在从 Nacos 加载服务列表...");
        refreshServices();

        // 定时刷新（固定延迟：上一次执行结束后再等待 interval，避免慢刷新堆积）
        int interval = nacosConfig.getRefreshInterval();
        if (interval > 0) {
            scheduler.scheduleWithFixedDelay(this::refreshServices,
                    interval, interval, TimeUnit.SECONDS);
            log.info("Nacos 服务列表定时刷新已启动，间隔 {} 秒", interval);
        }
    }

    /**
     * 刷新服务列表：连接 Nacos → 获取服务名 → 过滤 → 查询实例 → 注入 Registry。
     */
    public void refreshServices() {
        try {
            NamingService naming = getOrCreateNamingService();

            // 1. 获取全量服务列表
            ListView<String> services = naming.getServicesOfServer(1, Integer.MAX_VALUE, nacosConfig.getGroup());
            log.info("Nacos 查询到 {} 个服务（分组: {}）", services.getData().size(), nacosConfig.getGroup());

            // 2. 按 filter 过滤
            List<String> filtered = services.getData().stream()
                    .filter(this::shouldInclude)
                    .collect(Collectors.toList());
            log.info("过滤后保留 {} 个服务", filtered.size());

            // 3. 查询健康实例并构建 ServiceInstance
            List<ServiceInstance> instances = filtered.stream()
                    .map(name -> buildServiceInstance(naming, name))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("成功构建 {} 个 ServiceInstance", instances.size());

            // 4. 注入 Registry
            serviceRegistry.setServices(instances);

            instances.forEach(si -> log.info("  ✅ {} → baseUrl={}, port={}, routes={}",
                    si.getId(), si.getBaseUrl(), si.getPort(), si.getRoutePatterns()));

        } catch (NacosException e) {
            log.error("Nacos 服务发现刷新失败: {}", e.getErrMsg(), e);
        } catch (Exception e) {
            log.error("Nacos 服务发现刷新出现未知异常", e);
        }
    }

    /**
     * 获取（或首次创建）复用的 NamingService。
     * <p>
     * 双重检查锁保证线程安全且只创建一次。若创建失败（Nacos 暂不可达），
     * 保持 null 以便下次刷新时重试，避免一次失败导致后续永远无法恢复。
     */
    private NamingService getOrCreateNamingService() throws NacosException {
        NamingService local = this.naming;
        if (local == null) {
            synchronized (this) {
                local = this.naming;
                if (local == null) {
                    Properties props = new Properties();
                    props.put(PropertyKeyConst.SERVER_ADDR, nacosConfig.getServerAddr());
                    props.put(PropertyKeyConst.NAMESPACE, nacosConfig.getNamespace());
                    local = NamingFactory.createNamingService(props);
                    this.naming = local;
                }
            }
        }
        return local;
    }

    /**
     * 应用退出时释放 Nacos 客户端与调度线程，避免线程泄漏。
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        NamingService local = this.naming;
        if (local != null) {
            try {
                local.shutDown();
            } catch (NacosException e) {
                log.warn("关闭 Nacos NamingService 失败: {}", e.getErrMsg());
            }
        }
    }

    /**
     * 判断服务是否应被包含（先 exclude 后 include）。
     */
    private boolean shouldInclude(String serviceName) {
        NacosConfig.FilterConfig filter = nacosConfig.getFilter();
        if (!filter.isEnabled()) return true;

        // exclude 优先匹配
        if (matchAny(serviceName, filter.getExcludePatterns())) return false;

        // includePatterns 为空则全部通过
        if (filter.getIncludePatterns().isEmpty()) return true;

        return matchAny(serviceName, filter.getIncludePatterns());
    }

    private boolean matchAny(String name, List<String> patterns) {
        return patterns.stream().anyMatch(p -> pathMatcher.match(p, name));
    }

    /**
     * 查询健康实例并构建 ServiceInstance。
     * <p>
     * 路由模式按服务名约定推导：
     *   ENSEMBLE-RB-SERVICE → split("-")[1].toLowerCase() + "/**" → /rb/**
     * <p>
     * Comet URL 在 {@link com.mcp.scanner.CometApiSchemaLoader} 中按 Nacos 配置构造：
     *   {@code http://{nacosConfig.cometHost}:{service.port}}
     */
    private ServiceInstance buildServiceInstance(NamingService naming, String serviceName) {
        try {
            List<Instance> instances = naming.selectInstances(serviceName, nacosConfig.getGroup(), true);
            if (instances.isEmpty()) {
                log.warn("  服务 [{}] 无健康实例（分组: {}），跳过", serviceName, nacosConfig.getGroup());
                return null;
            }

            Instance inst = instances.get(0);  // 取第一个健康实例
            String baseUrl = String.format("http://%s:%d", inst.getIp(), inst.getPort());

            ServiceInstance si = new ServiceInstance();
            si.setId(serviceName);
            si.setBaseUrl(baseUrl);
            si.setPort(inst.getPort());
            si.setRoutePatterns(determineRoutePatterns(serviceName));

            return si;
        } catch (NacosException e) {
            log.error("  查询服务 [{}] 实例失败: {}", serviceName, e.getErrMsg());
            return null;
        }
    }

    /**
     * 按服务名约定推导路由模式。
     * ENSEMBLE-RB-SERVICE → [/rb/**]
     * ENSEMBLE-OB-SERVICE → [/ob/**]
     */
    static List<String> determineRoutePatterns(String serviceName) {
        String[] parts = serviceName.split("-");
        if (parts.length >= 2) {
            String module = parts[1].toLowerCase();
            return List.of("/" + module + "/**");
        }
        return List.of("/**");
    }
}
