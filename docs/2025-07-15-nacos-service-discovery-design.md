 # MCP Adapter Nacos 服务发现改造方案

> 日期：2025-07-15
> 状态：设计稿 v1

---

## 1. 背景与目标

### 1.1 现状问题

当前 `mcp-adapter` 的后端服务地址全部**硬编码**在 `application.yml` 中：

```yaml
# 单服务模式
service:
  original:
    url: http://127.0.0.1:9020

# 多服务集群模式（已注释）
service:
  registry:
    services:
      - id: service-pf
        base-url: http://10.127.7.141:9021
```

每次服务地址变更都需要修改配置并重启，无法适应动态变化的微服务环境。

### 1.2 改造目标

1. **Nacos 服务发现**：从 Nacos 动态获取后端微服务的 IP:Port，替代硬编码地址
2. **服务过滤开关**：通过配置按服务名过滤，实现选择性启用（例如 5 个服务只启用 RB 相关服务）
3. **最小改动**：保持 `ServiceRouter`、`HttpForwarder` 等核心路由/转发逻辑不变

### 1.3 环境信息

| 配置项 | 值 |
|--------|-----|
| Nacos 地址 | `http://192.168.161.214:8848/nacos` |
| 命名空间 | `online-batch_uat` |
| 分组 | `online-batch-uat` |
| 认证 | 不需要 |
| 服务名示例 | `ENSEMBLE-OB-SERVICE`, `ENSEMBLE-RB-SERVICE` |

---

## 2. 方案对比

### 方案 A：Nacos Java SDK（★ 推荐）

使用 Nacos 官方 `nacos-client` SDK，轻量级接入，只引入一个依赖。

| 维度 | 评估 |
|------|------|
| 依赖 | 仅 `nacos-client` 一个 |
| 侵入性 | 低——`ServiceRouter`/`HttpForwarder` 不改 |
| 过滤 | AntPath 模式匹配，灵活支持 include/exclude |
| 动态刷新 | 支持 Nacos Watch 或定时轮询 |
| 与现有架构匹配度 | 高——项目为纯 Spring Boot，无 Spring Cloud |

### 方案 B：Spring Cloud Alibaba Nacos Discovery

引入 `spring-cloud-starter-alibaba-nacos-discovery`。

| 维度 | 评估 |
|------|------|
| 依赖 | 引入 Spring Cloud 全家桶，版本对齐复杂 |
| 侵入性 | 中——需要 `@EnableDiscoveryClient` |
| 过滤 | 仍需自行实现 |
| 动态刷新 | 自带 |
| 与现有架构匹配度 | 低——本项目无需负载均衡/断路器 |

### 方案 C：Nacos Config 维护服务列表

在 Nacos Config 中维护 JSON 服务列表。

| 维度 | 评估 |
|------|------|
| 冗余 | 服务已在 Nacos Discovery 注册，再维护一份配置属重复管理 |
| 实时性 | 弱于原生服务发现 |
| 推荐度 | ❌ 不推荐 |

---

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     application.yml                             │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ service.nacos                                            │    │
│  │   server-addr: http://192.168.161.214:8848/nacos         │    │
│  │   namespace: online-batch_uat                            │    │
│  │   group: online-batch-uat                                │    │
│  │   comet-host: 10.127.7.140                               │    │
│  │   filter.include-patterns: [ENSEMBLE-RB-*, ENSEMBLE-OB-*]│    │
│  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────────────┘
                           │ @ConfigurationProperties
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    NacosConfig (新增)                            │
│  @ConfigurationProperties(prefix = "service.nacos")             │
│  - serverAddr, namespace, group, cometHost                      │
│  - filter (includePatterns, excludePatterns)                    │
│  - refreshInterval                                              │
└──────────────────────────┬──────────────────────────────────────┘
                           │ inject
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                NacosServiceDiscoverer (新增)                     │
│  ┌─────────────────┐    ┌──────────────────┐                   │
│  │ 1. 连接 Nacos    │ →  │ 2. 获取服务列表   │                  │
│  └─────────────────┘    └────────┬─────────┘                   │
│                                  ▼                              │
│  ┌─────────────────┐    ┌──────────────────┐                   │
│  │ 4. 查询健康实例  │ ←  │ 3. 过滤服务名     │                  │
│  │    (Instances)   │    │    (AntPath)     │                   │
│  └────────┬────────┘    └──────────────────┘                   │
│           ▼                                                     │
│  ┌──────────────────────────────────────────┐                  │
│  │ 5. 构建 ServiceInstance + 注入 Registry   │                  │
│  └──────────────────────────────────────────┘                  │
└──────────────────────────┬──────────────────────────────────────┘
                           │ populate
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│               ServiceRegistry (改造)                             │
│  List<ServiceInstance> ← 现在由 Nacos 填充                       │
└──────────────┬──────────────────────────────────────────────────┘
               │ lookup
               ▼
┌──────────────────────────────┐    ┌─────────────────────────────┐
│    ServiceRouter (不改)       │    │  CometApiSchemaLoader (改造)│
│  AntPathMatcher 路由          │    │  comet-host = nacos配置     │
│  返回 ServiceInstance.baseUrl │    │  comet-port = 服务端口      │
└──────────────┬───────────────┘    └─────────────────────────────┘
               │ route()
               ▼
┌─────────────────────────────────────────────────────────────────┐
│               HttpForwarder (不改)                               │
│  POST {baseUrl}{apiPath}                                        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 详细设计

### 4.1 新增配置类：`NacosConfig.java`

**路径**：`src/main/java/com/mcp/config/NacosConfig.java`

```java
@Configuration
@ConfigurationProperties(prefix = "service.nacos")
public class NacosConfig {
    private boolean enabled = false;
    private String serverAddr;
    private String namespace;
    private String group = "DEFAULT_GROUP";
    private String cometHost;
    private int refreshInterval = 30;  // 秒，0=不刷新
    private FilterConfig filter = new FilterConfig();

    @Getter @Setter
    public static class FilterConfig {
        private boolean enabled = false;
        private List<String> includePatterns = List.of();  // 空=全部
        private List<String> excludePatterns = List.of();
    }
}
```

### 4.2 新增核心类：`NacosServiceDiscoverer.java`

**路径**：`src/main/java/com/mcp/config/NacosServiceDiscoverer.java`

职责：
1. 启动时连接 Nacos，查询指定命名空间+分组下的所有服务
2. 按 `filter.includePatterns` / `excludePatterns` 过滤服务名
3. 对每个匹配的服务，查询健康实例（`healthy=true`）
4. 构建 `ServiceInstance` 对象，注入 `ServiceRegistry`
5. （可选）定时刷新或通过 Nacos Watch 感知变化

关键实现：

```java
@Component
@RequiredArgsConstructor
public class NacosServiceDiscoverer {

    private final NacosConfig nacosConfig;
    private final ServiceRegistry serviceRegistry;

    @PostConstruct
    public void init() {
        if (!nacosConfig.isEnabled()) return;
        refreshServices();
    }

    public void refreshServices() {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, nacosConfig.getServerAddr());
        props.put(PropertyKeyConst.NAMESPACE, nacosConfig.getNamespace());

        NamingService naming = NamingFactory.createNamingService(props);
        
        // 1. 获取全量服务列表
        ListView<String> services = naming.getServicesOfServer(1, Integer.MAX_VALUE, nacosConfig.getGroup());
        
        // 2. 过滤
        List<String> filtered = services.getData().stream()
            .filter(this::shouldInclude)
            .collect(Collectors.toList());
        
        // 3. 查询实例并构建 ServiceInstance
        List<ServiceInstance> instances = filtered.stream()
            .map(name -> buildServiceInstance(naming, name))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        // 4. 注入 Registry
        serviceRegistry.setServices(instances);
    }

    private boolean shouldInclude(String serviceName) {
        FilterConfig filter = nacosConfig.getFilter();
        if (!filter.isEnabled()) return true;
        
        // exclude 优先
        if (matchAny(serviceName, filter.getExcludePatterns())) return false;
        
        // includePatterns 为空则全部通过
        if (filter.getIncludePatterns().isEmpty()) return true;
        
        return matchAny(serviceName, filter.getIncludePatterns());
    }

    private boolean matchAny(String name, List<String> patterns) {
        AntPathMatcher matcher = new AntPathMatcher();
        return patterns.stream().anyMatch(p -> matcher.match(p, name));
    }

    private ServiceInstance buildServiceInstance(NamingService naming, String serviceName) {
        try {
            List<Instance> instances = naming.selectInstances(serviceName, true);
            if (instances.isEmpty()) return null;
            
            Instance inst = instances.get(0);  // 取第一个健康实例
            String baseUrl = String.format("http://%s:%d", inst.getIp(), inst.getPort());
            
            ServiceInstance si = new ServiceInstance();
            si.setId(serviceName);
            si.setBaseUrl(baseUrl);
            si.setPort(inst.getPort());
            // 路由模式按服务名约定推导：
            // ENSEMBLE-RB-SERVICE → [/rb/**]
            // ENSEMBLE-OB-SERVICE → [/ob/**]
            // 规则：取服务名第二段（小写）+ "/**"
            si.setRoutePatterns(determineRoutePatterns(serviceName));
            
            return si;
        } catch (NacosException e) {
            log.error("Failed to query instances for {}", serviceName, e);
            return null;
        }
    }
}
```

**关于端口透传**：`ServiceInstance` 需要增加一个 `port` 字段（或从 `baseUrl` 解析），供 `CometApiSchemaLoader` 构造 Comet 地址时使用。

### 4.3 改造：`ServiceRegistry.java`

在原基础上增加 Nacos 的写入入口：

```java
@Component
@ConfigurationProperties(prefix = "service.registry")
public class ServiceRegistry {
    private List<ServiceInstance> services = new ArrayList<>();
    
    // 新增：Nacos 模式下覆盖
    public void setServices(List<ServiceInstance> services) {
        this.services = services;
    }
    
    // 原有方法保持兼容
    public List<ServiceInstance> getServices() { ... }
    public ServiceInstance findById(String id) { ... }
}
```

### 4.4 改造：`ServiceInstance.java`

增加 `port` 字段供 Comet 地址推导：

```java
public class ServiceInstance {
    private String id;
    private String baseUrl;
    private int port;              // 新增
    private List<String> routePatterns;
    // CometConfig comet;          // 不再需要——Comet 地址改为从 NacosConfig + port 推导
}
```

### 4.4a 路由模式推导规则

从 Nacos 发现服务时，`routePatterns` 按服务名约定自动推导。

**规则**：服务名格式为 `ENSEMBLE-{模块名}-SERVICE`，取中间段转小写作为路径前缀。

| 服务名 | 推导的 routePatterns |
|--------|---------------------|
| `ENSEMBLE-RB-SERVICE` | `[/rb/**]` |
| `ENSEMBLE-OB-SERVICE` | `[/ob/**]` |
| `ENSEMBLE-PF-SERVICE` | `[/pf/**]` |
| `ENSEMBLE-GW-SERVICE` | `[/gw/**]` |
| `ENSEMBLE-COM-SERVICE` | `[/com/**]` |

实现方式：
```java
private List<String> determineRoutePatterns(String serviceName) {
    // ENSEMBLE-RB-SERVICE → RB → /rb/**
    String[] parts = serviceName.split("-");
    if (parts.length >= 2) {
        String module = parts[1].toLowerCase();
        return List.of("/" + module + "/**");
    }
    // fallback: 通配全部
    return List.of("/**");
}
```

> 如果未来有特例，可通过 Nacos 实例 metadata 覆盖，或回退到 YAML 中单独配置映射表。

### 4.5 改造：`CometApiSchemaLoader.java`

Comet 地址构造逻辑从 `serviceInstance.getComet()` 改为：

```java
// 旧逻辑
String cometBase = String.format("http://%s:%d", 
    service.getComet().getHost(), service.getComet().getPort());

// 新逻辑（当 Nacos 启用时）
String cometBase = String.format("http://%s:%d",
    nacosConfig.getCometHost(), service.getPort());
```

当 Nacos 未启用时，回退到原有逻辑。

### 4.6 新增 `application.yml` 配置段

```yaml
service:
  # Nacos 服务发现
  nacos:
    enabled: true
    server-addr: http://192.168.161.214:8848/nacos
    namespace: online-batch_uat
    group: online-batch-uat
    
    # Comet 平台（所有服务共用同一个 Comet 主机）
    comet-host: 10.127.7.140
    
    # 服务过滤
    filter:
      enabled: true
      include-patterns:
        - ENSEMBLE-RB-*
        - ENSEMBLE-OB-*
      exclude-patterns: []
    
    # 定时刷新（秒）
    refresh-interval: 30

  # 原有配置保留做兜底
  original:
    url: http://127.0.0.1:9020
```

### 4.7 `pom.xml` 新增依赖

```xml
<!-- Nacos Client -->
<dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>nacos-client</artifactId>
    <version>2.2.4</version>
</dependency>
```

---

## 5. 改动的文件清单

### 新增文件
| 文件 | 说明 |
|------|------|
| `src/main/java/com/mcp/config/NacosConfig.java` | Nacos 配置属性绑定 |
| `src/main/java/com/mcp/config/NacosServiceDiscoverer.java` | Nacos 服务发现核心逻辑 |

### 修改文件
| 文件 | 改动点 |
|------|--------|
| `src/main/java/com/mcp/config/ServiceInstance.java` | 增加 `port` 字段 |
| `src/main/java/com/mcp/config/ServiceRegistry.java` | 增加 `setServices()` 方法 |
| `src/main/java/com/mcp/scanner/CometApiSchemaLoader.java` | Comet 地址构造逻辑（Nacos 模式走新路径） |
| `src/main/resources/application.yml` | 新增 `service.nacos` 配置段 |
| `pom.xml` | 新增 `nacos-client` 依赖 |

### 不改动的文件
| 文件 | 原因 |
|------|------|
| `ServiceRouter.java` | 路由逻辑基于 `ServiceInstance`，不关心数据来源 |
| `HttpForwarder.java` | URL 拼接逻辑不变 |
| `DynamicToolRegistrar.java` | 工具注册逻辑不变 |
| `AppConfig.java` | 兜底模式保留 |
| 其余 `tool/` 包文件 | 无影响 |

---

## 6. 服务过滤效果示例

假设 Nacos 上注册了以下服务：

| 服务名 | include-patterns 匹配 |
|--------|:---------------------:|
| `ENSEMBLE-RB-SERVICE` | ✅ `RB-*` 匹配 |
| `ENSEMBLE-OB-SERVICE` | ❌ 不匹配 |
| `ENSEMBLE-GW-SERVICE` | ❌ 不匹配 |
| `ENSEMBLE-PF-SERVICE` | ❌ 不匹配 |
| `ENSEMBLE-COM-SERVICE` | ❌ 不匹配 |

配置 `include-patterns: ["ENSEMBLE-RB-*", "ENSEMBLE-OB-*"]` → **仅加载 RB 和 OB 两个服务**。

如果想加载全部服务，只需清空 include-patterns 或改为 `["**"]`。

---

## 7. 数据流

```
Spring Boot 启动
    │
    ├── NacosServiceDiscoverer.init()
    │   ├── NamingFactory.createNamingService(props)
    │   ├── naming.getServicesOfServer(1, MAX, group)  → 获取全部服务名
    │   ├── filter(shouldInclude)                       → 按 pattern 过滤
    │   ├── forEach: naming.selectInstances(name, true) → 查询健康实例
    │   └── serviceRegistry.setServices(instances)      → 注入
    │
    ├── CometApiSchemaLoader (异步)
    │   └── 遍历 ServiceRegistry → 构造 comet URL:
    │       http://{nacosConfig.cometHost}:{service.port}/comet-interface
    │
    └── 请求处理 (运行时)
        └── HttpForwarder.forwardPost(apiPath)
            └── ServiceRouter.route(apiPath) → ServiceInstance.baseUrl
                └── POST {baseUrl}/{apiPath}
```

---

## 8. 验证方式

1. **启动日志验证**：观察日志输出，确认 Nacos 连接成功、服务列表正确过滤
2. **Nacos 连接异常处理**：断网后应有 fallback 日志，不阻塞启动
3. **服务过滤验证**：修改 `include-patterns` 配置，重启后确认只有匹配的服务被加载
4. **接口调用验证**：选定一个已过滤加载的服务，调用其接口确认能正常响应
5. **Comet Schema 加载验证**：确认 Comet 地址正确，接口 Schema 加载成功
6. **Nacos 未启用时回退验证**：`nacos.enabled: false` 应回退到原有硬编码模式

---

## 9. 边界情况与错误处理

| 场景 | 处理方式 |
|------|----------|
| Nacos 连接超时 | 日志警告，回退到原有硬编码配置（如果存在） |
| 过滤后无匹配服务 | 日志警告，`ServiceRegistry` 为空列表，调用时返回 502 |
| Nacos 实例数为 0 | 日志警告，该服务不注册，跳过 |
| 多实例负载 | 取第一个健康实例（当前无负载均衡需求，可后续扩展） |
| Nacos Watch 断开 | 定时 refresh 兜底；如果连接恢复自动重连 |
| 服务端口变化 | 下个 refresh 周期自动更新 |
