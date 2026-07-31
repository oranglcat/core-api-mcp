package com.mcp.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.config.ServiceInstance;
import com.mcp.config.ServiceRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Comet 接口文档平台 REST API 加载器。
 * <p>
 * 通过调用 Comet 平台的 REST API 获取所有接口的入参和出参定义。
 * 支持两种模式：
 * <ul>
 *   <li><b>多服务模式</b>：通过 {@link ServiceRegistry} 配置多个后端微服务，
 *       每个服务的 Schema 以异步并行方式加载，不阻塞应用启动</li>
 *   <li><b>单服务模式</b>（向后兼容）：通过 {@code service.comet.base-url} 配置单个 Comet 地址</li>
 * </ul>
 * <p>
 * 当后台加载尚未完成时，可通过 {@link #loadRemainingOnDemand()} 进行按需同步加载。
 */
@Component
@DependsOn("nacosServiceDiscoverer")
public class CometApiSchemaLoader {

    private static final Logger log = LoggerFactory.getLogger(CometApiSchemaLoader.class);

    private final ServiceRegistry serviceRegistry;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** Spring 事件发布器，Schema 加载完成后发布事件触发动态工具注册 */
    private final ApplicationEventPublisher eventPublisher;

    /** URL → ApiParamSchema */
    private final Map<String, ApiParamSchema> schemaMap = new ConcurrentHashMap<>();

    /** 用于标记加载状态的 volatile 标志位（后台任务全部完成时才标记为 true） */
    private volatile boolean loaded = false;

    /** 后台加载是否仍在进行中 */
    private volatile boolean loadingInProgress = false;

    private String loadError = null;

    /** 已完全加载的服务 ID 集合 */
    private final Set<String> fullyLoadedServices = ConcurrentHashMap.newKeySet();

    /** 加载失败的服务 ID 集合 */
    private final Set<String> failedServices = ConcurrentHashMap.newKeySet();

    /**
     * 加载完成门闩。
     * <p>
     * 当所有服务的 Schema 异步加载完毕时释放。
     * 超时兜底：若有服务 Comet 不可达，最多等待 30 秒后继续，不会永久阻塞。
     */
    private final CountDownLatch loadLatch = new CountDownLatch(1);

    /** 多服务异步加载线程池 */
    private final ExecutorService asyncExecutor;

    public CometApiSchemaLoader(ServiceRegistry serviceRegistry,
                                ObjectMapper objectMapper,
                                ApplicationEventPublisher eventPublisher) {
        this.serviceRegistry = serviceRegistry;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;

        // Comet API 请求超时（与服务调用一致）
        int timeout = 5000;
        this.restTemplate = new RestTemplate();
        this.restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
            setConnectTimeout(timeout);
            setReadTimeout(timeout);
        }});

        // 线程池大小：最多 8 个并行，避免压垮网络
        int poolSize = Math.min(
            Runtime.getRuntime().availableProcessors() * 2,
            8
        );
        this.asyncExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "comet-loader-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private static final AtomicInteger counter = new AtomicInteger(0);

    /** 防重复发布事件 */
    private final AtomicBoolean eventPublished = new AtomicBoolean(false);

    /** 优雅关闭时等待线程池终止的超时秒数 */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 3;

    /**
     * 应用启动时加载所有接口的参数定义。
     * <p>
     * 多服务模式：异步并行加载，不阻塞 Spring 容器就绪。
     * 单服务模式：同步加载（保持向后兼容）。
     */
    @PostConstruct
    public void load() {
        if (!serviceRegistry.hasServices()) {
            log.info("Comet 未配置（无 Nacos 服务），跳过 Schema 加载");
            this.loaded = false;
            this.loadingInProgress = false;
            return;
        }

        // ======== 多服务模式（Nacos 动态发现，异步并行加载） ========
        List<ServiceInstance> services = serviceRegistry.getServices();

        log.info("多服务模式：开始异步加载 {} 个服务的 Comet Schema（不阻塞启动）", services.size());
        this.loadingInProgress = true;

        for (ServiceInstance service : services) {
            asyncExecutor.submit(() -> {
                String serviceId = service.getId();
                long start = System.currentTimeMillis();
                boolean success = false;

                // Comet API 在微服务自身的主机端口上（无需独立 Comet 地址）
                try {
                    String cometUrl = service.getBaseUrl();
                    int count = loadFromSingleComet(cometUrl, serviceId);
                    fullyLoadedServices.add(serviceId);
                    log.info("服务 [{}] Schema 加载完成 ({} 个接口, {}ms)",
                        serviceId, count, System.currentTimeMillis() - start);
                    success = true;
                } catch (Exception e) {
                    log.error("服务 [{}] Comet 连接失败: {}", serviceId, e.getMessage());
                }

                // 记录失败
                if (!success) {
                    failedServices.add(serviceId);
                    log.error("服务 [{}] Schema 加载失败 ({}ms)", serviceId, System.currentTimeMillis() - start);
                }

                // 检查是否全部完成（仅首次触发）
                checkAndFireCompletion(services.size());
            });
        }

        // 不等待，立即返回 —— Spring 容器继续初始化
        log.info("已提交 {} 个服务的 Schema 加载任务（后台执行中）", services.size());
    }

    // ========== 单 Comet 加载逻辑 ==========

    /**
     * 从指定 Comet 地址加载其所有接口的参数 Schema。
     *
     * @param baseUrl   Comet 基础 URL（如 http://10.127.7.141:9020）
     * @param serviceId 所属服务 ID（多服务模式传入，单服务模式传 null）
     * @return 成功加载的接口数量
     */
    private int loadFromSingleComet(String baseUrl, String serviceId) {
        // 1. 获取接口列表
        List<Map<String, Object>> interfaces = fetchInterfaceList(baseUrl);
        log.info("  Comet {}: 获取到 {} 个接口", obscureUrl(baseUrl), interfaces.size());

        if (interfaces.isEmpty()) {
            log.warn("  Comet {}: 无接口返回", obscureUrl(baseUrl));
            return 0;
        }

        // 2. 逐个获取入参/出参（带简单速率控制）
        int successCount = 0;
        for (int i = 0; i < interfaces.size(); i++) {
            Map<String, Object> iface = interfaces.get(i);
            String path = iface.get("path") instanceof String ? (String) iface.get("path") : null;
            String apiName = iface.get("name") instanceof String ? (String) iface.get("name") : "";
            String serverId = iface.get("serverId") instanceof String ? (String) iface.get("serverId") : "";
            String className = iface.get("className") instanceof String ? (String) iface.get("className") : "";
            String methodName = iface.get("methodName") instanceof String ? (String) iface.get("methodName") : "";
            String remark = iface.get("remark") instanceof String ? (String) iface.get("remark") : "";

            if (path == null || path.isBlank()) continue;

            path = normalizeUrl(path);

            try {
                // 获取入参
                List<Map<String, Object>> rawInputs = fetchFields(baseUrl + "/comet-interface-field", path);
                List<FieldDef> inputs = convertToFieldDefs(rawInputs);

                // 获取出参
                List<Map<String, Object>> rawOutputs = fetchFields(baseUrl + "/comet-interface-field-output", path);
                List<FieldDef> outputs = convertToFieldDefs(rawOutputs);

                schemaMap.put(path, new ApiParamSchema(serviceId, path, apiName,
                    serverId, className, methodName, remark, inputs, outputs));
                successCount++;

                if ((i + 1) % 10 == 0) {
                    log.info("  Progress: {}/{} interfaces loaded from {}", i + 1, interfaces.size(), obscureUrl(baseUrl));
                }

            } catch (Exception e) {
                log.warn("  加载接口 [{}/{}] {} 失败: {}", serviceId != null ? serviceId : "", path, apiName, e.getMessage());
            }
        }

        return successCount;
    }

    // ========== HTTP 请求 ==========

    /** 页面响应（列表 + 总条数） */
    private record PageResult(List<Map<String, Object>> list, int total) {}

    /** 获取一页接口列表 */
    @SuppressWarnings("unchecked")
    private PageResult fetchInterfacePage(String baseUrl, int pageIndex, int pageSize) {
        String url = baseUrl + "/comet-interface";

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("appHead", Map.of("totalNum", "SS"));
        requestBody.put("sysHead", Map.of(
            "branchId", "10001",
            "seqNo", "12345678900987654321",
            "sourceType", "TAE",
            "tranCode", "10001",
            "tranDate", "20200202",
            "tranType", ""
        ));
        requestBody.put("pageIndex", pageIndex);
        requestBody.put("pageSize", pageSize);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) return new PageResult(List.of(), 0);

        // 尝试从不同位置读取总条数
        int total = 0;
        Object totalObj = body.get("total");
        if (totalObj instanceof Number) {
            total = ((Number) totalObj).intValue();
        } else if (totalObj instanceof String) {
            try { total = Integer.parseInt((String) totalObj); } catch (NumberFormatException e) { log.debug("解析 total 失败: {}", totalObj, e); }
        }
        // 也尝试从 appHead 中读取
        if (total == 0) {
            Object appHead = body.get("appHead");
            if (appHead instanceof Map) {
                Object totalNum = ((Map<String, Object>) appHead).get("totalNum");
                if (totalNum instanceof Number) {
                    total = ((Number) totalNum).intValue();
                }
            }
        }

        Object listObj = body.get("list");
        if (listObj instanceof List) {
            return new PageResult((List<Map<String, Object>>) listObj, total);
        }
        return new PageResult(List.of(), total);
    }

    /** 全量分页获取接口列表 */
    private List<Map<String, Object>> fetchInterfaceList(String baseUrl) {
        int pageSize = 200;
        int pageIndex = 1;
        List<Map<String, Object>> allInterfaces = new ArrayList<>();
        int total = 0;

        for (;; pageIndex++) {
            PageResult page = fetchInterfacePage(baseUrl, pageIndex, pageSize);
            if (page.list.isEmpty()) break;          // 空页 → 结束
            allInterfaces.addAll(page.list);

            if (total == 0 && page.total > 0) {
                total = page.total;                  // 首次拿到总条数
            }
            if (total > 0) {                         // 知道总条数 → 算总页数
                int totalPages = (total + pageSize - 1) / pageSize;
                if (pageIndex >= totalPages) break;
            }
            // 不知道总条数 → 继续读到空页为止
        }

        return allInterfaces;
    }

    /** 获取入参或出参字段定义 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchFields(String url, String path) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("appHead", Map.of("totalNum", "SS"));
        requestBody.put("sysHead", Map.of(
            "branchId", "10001",
            "seqNo", "12345678900987654321",
            "sourceType", "TAE",
            "tranCode", "10001",
            "tranDate", "20200202",
            "tranType", ""
        ));
        requestBody.put("path", path);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.POST, entity, List.class);
        List<Map<String, Object>> fields = response.getBody();
        return fields != null ? fields : List.of();
    }

    // ========== 字段转换 ==========

    /**
     * 将 Comet API 返回的字段列表转换为 {@link FieldDef} 列表。
     * <p>
     * 对于入参，Comet 通常返回一个 "body" 容器字段（type=Body），
     * 实际参数在其 fieldInfos 中，需要做解包处理。
     */
    private List<FieldDef> convertToFieldDefs(List<Map<String, Object>> rawFields) {
        if (rawFields == null || rawFields.isEmpty()) return List.of();

        // 解包 body 容器：如果只有一个字段且 type=Body/OBJECT，取其 children
        if (rawFields.size() == 1) {
            Map<String, Object> first = rawFields.get(0);
            String type = str(first.get("type"));
            if ("Body".equalsIgnoreCase(type) || "OBJECT".equalsIgnoreCase(type)) {
                Object children = first.get("fieldInfos");
                if (children instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> childList = (List<Map<String, Object>>) children;
                    if (!childList.isEmpty()) {
                        return childList.stream()
                            .map(this::convertSingleField)
                            .filter(Objects::nonNull)
                            .toList();
                    }
                }
            }
        }

        return rawFields.stream()
            .map(this::convertSingleField)
            .filter(Objects::nonNull)
            .toList();
    }

    /** 将单个 Comet 字段映射为 FieldDef */
    @SuppressWarnings("unchecked")
    private FieldDef convertSingleField(Map<String, Object> raw) {
        if (raw == null) return null;

        String name = str(raw.get("name"));
        if (name == null || name.isBlank()) return null;

        String type = str(raw.get("type"));
        String desc = str(raw.get("desc"));
        boolean must = bool(raw.get("must"));
        String length = str(raw.get("length"));
        String scope = str(raw.get("scope"));
        String remark = str(raw.get("remark"));

        // 从 length 构造类型字符串（如 "50" → "String(50)"）
        String fullType = type;
        if (length != null && !length.isBlank() && !"List".equals(type) && !"Body".equals(type)) {
            fullType = type + "(" + length + ")";
        }

        // 提取最大最小长度
        Integer maxLength = parseInt(length);

        // 处理嵌套子字段
        List<FieldDef> children = null;
        Object fieldInfos = raw.get("fieldInfos");
        if (fieldInfos instanceof List) {
            List<Map<String, Object>> childList = (List<Map<String, Object>>) fieldInfos;
            if (!childList.isEmpty()) {
                children = childList.stream()
                    .map(this::convertSingleField)
                    .filter(Objects::nonNull)
                    .toList();
            }
        }

        return new FieldDef(name, fullType, desc, must, scope, null, null,
            maxLength, null, remark, children);
    }

    // ========== 按需加载 ==========

    /**
     * 按需同步加载尚未完成的服务的所有 Schema。
     * <p>
     * 当 {@link com.mcp.tool.DynamicToolRegistrar} 收到一个工具调用请求，
     * 但对应 apiCode 在 {@code codeToSchema} 中不存在时，调用此方法。
     * 它会遍历所有尚未加载/尚未失败的服务，同步加载其 Comet Schema，
     * 然后调用者可重试查找。
     */
    public synchronized void loadRemainingOnDemand() {
        if (!loadingInProgress) {
            log.debug("按需加载跳过：所有服务已加载完毕");
            return;
        }

        List<ServiceInstance> pendingServices = serviceRegistry.getServices()
            .stream()
            .filter(s -> !fullyLoadedServices.contains(s.getId()))
            .filter(s -> !failedServices.contains(s.getId()))
            .collect(Collectors.toList());

        if (pendingServices.isEmpty()) {
            // 可能所有服务都已加载完但 loadingInProgress 还没更新
            checkAndFireCompletion((int) serviceRegistry.getServices().size());
            return;
        }

        log.info("按需加载：同步加载 {} 个尚未完成的服务的 Schema", pendingServices.size());

        for (ServiceInstance service : pendingServices) {
            String serviceId = service.getId();
            long start = System.currentTimeMillis();
            boolean success = false;

            try {
                String cometUrl = service.getBaseUrl();
                int count = loadFromSingleComet(cometUrl, serviceId);
                fullyLoadedServices.add(serviceId);
                log.info("按需加载服务 [{}] 完成 ({} 个接口, {}ms)",
                    serviceId, count, System.currentTimeMillis() - start);
                success = true;
            } catch (Exception e) {
                log.error("按需加载服务 [{}] Comet 连接失败: {}", serviceId, e.getMessage());
            }

            if (!success) {
                failedServices.add(serviceId);
                log.error("按需加载服务 [{}] 失败 ({}ms)", serviceId, System.currentTimeMillis() - start);
            }
        }

        // 检查是否全部完成
        checkAndFireCompletion(serviceRegistry.getServices().size());
    }

    // ========== 工具方法 ==========

    /** 标准化 URL 路径 */
    private String normalizeUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        if (!url.startsWith("/")) url = "/" + url;
        if (url.endsWith("/") && url.length() > 1) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 安全获取字符串 */
    private String str(Object val) {
        return val != null ? val.toString().trim() : "";
    }

    /** 安全获取布尔值 */
    private boolean bool(Object val) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return "Y".equalsIgnoreCase((String) val);
        return false;
    }

    /** 安全解析整数 */
    private Integer parseInt(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 混淆 URL 中的主机信息（日志脱敏用）。
     * http://10.127.7.141:9020 → http://***:9020
     */
    private String obscureUrl(String url) {
        if (url == null) return null;
        try {
            int colonSlashSlash = url.indexOf("://");
            if (colonSlashSlash < 0) return url;
            String protocol = url.substring(0, colonSlashSlash + 3);
            String rest = url.substring(colonSlashSlash + 3);
            int colonPort = rest.indexOf(':');
            if (colonPort > 0) {
                return protocol + "***" + rest.substring(colonPort);
            }
            return protocol + "***";
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 检查是否所有服务 Schema 均已加载完毕（成功 + 失败），
     * 若首次满足条件则触发事件通知动态工具注册。
     */
    private void checkAndFireCompletion(int totalServices) {
        if (fullyLoadedServices.size() + failedServices.size() >= totalServices
            && eventPublished.compareAndSet(false, true)) {
            this.loaded = true;
            this.loadingInProgress = false;
            log.info("所有服务 Schema 加载完毕。成功: {}, 失败: {}",
                fullyLoadedServices.size(), failedServices.size());
            loadLatch.countDown();
            eventPublisher.publishEvent(new SchemaLoadCompleteEvent(this));
        }
    }

    // ========== 公开方法 ==========

    /**
     * 根据 URL 路径获取接口参数定义。
     *
     * @param url API 路径
     * @return 参数定义，不存在时返回 null
     */
    public ApiParamSchema getByUrl(String url) {
        if (url == null) return null;
        return schemaMap.get(normalizeUrl(url));
    }

    public boolean isLoaded() { return loaded; }
    public String getLoadError() { return loadError; }
    public boolean isLoadingInProgress() { return loadingInProgress; }
    public boolean isServiceFullyLoaded(String serviceId) { return fullyLoadedServices.contains(serviceId); }
    public Set<String> getFailedServices() { return Collections.unmodifiableSet(failedServices); }

    /**
     * 等待异步加载完成（最多等待指定时间）。
     * <p>
     * 由 {@link com.mcp.tool.DynamicToolRegistrar#getToolCallbacks()} 调用，
     * 确保在 Spring AI MCP Server 初始化时工具列表已经就绪。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return true 表示加载已完成（无论成功或失败）；false 表示超时
     */
    public boolean awaitLoadingComplete(long timeout, TimeUnit unit) {
        if (loaded) return true;
        if (!loadingInProgress) return loaded;
        try {
            boolean released = loadLatch.await(timeout, unit);
            if (!released) {
                log.warn("等待 Schema 异步加载超时 ({} {})，部分服务可能仍未加载完成",
                    timeout, unit);
            }
            return released;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 Schema 异步加载被中断");
            return loaded;
        }
    }

    /**
     * 获取 Schema 加载完成度的摘要字符串（用于日志输出）。
     */
    public String getLoadSummary() {
        long total = serviceRegistry.hasServices()
            ? serviceRegistry.getServices().size()
            : 1;
        return String.format("%d/%d (成功: %d, 失败: %d)",
            fullyLoadedServices.size() + failedServices.size(),
            total, fullyLoadedServices.size(), failedServices.size());
    }

    public Map<String, ApiParamSchema> getAllSchemas() {
        return Collections.unmodifiableMap(schemaMap);
    }

    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
