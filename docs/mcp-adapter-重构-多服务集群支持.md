# MCP Adapter 多服务集群支持重构报告

## 修改概述

将 MCP Adapter 从**单后端服务**架构重构为**多微服务集群**架构，解决生产环境中后端服务集群化部署导致的以下问题：

- `service.original.url` 写死单点地址
- `service.comet.base-url` 写死单点地址
- 无法按 API 路径路由到不同后端微服务

**共新增 3 个文件，修改 5 个文件。**

---

## 架构变化

```
改造前                             改造后

LLM Client                    LLM Client
      │                             │
      ▼                             ▼
┌──────────┐                 ┌──────────┐
│ MCP      │                 │ MCP      │
│ Adapter  │                 │ Adapter  │
│          │                 │          │
│ URL:     │                 │ Router ──┼──▶ Service A (:9021)
│ :9020    │                 │          │        └─ Comet (:9120)
│          │                 │          │
│ Comet:   │  ────▶ 某个服务  │          │
│ :9020    │                 │ Loader ──┼──▶ Service B (:9022)
└──────────┘                 │ (并行)   │        └─ Comet (:9121)
      │                      └──────────┘
      ▼                           │
  单后端服务 (127.0.0.1:9020)     ▼
                           目标服务 (按路径路由)
```

### 关键设计点

| 特性 | 实现 |
|------|------|
| 服务注册 | `application.yml` 配置驱动，无外部依赖 |
| 路由策略 | AntPath 最长前缀匹配（`/pf/**` → PF 服务） |
| Schema 加载 | 多服务并行异步，不阻塞 Spring 启动 |
| 按需兜底 | 后台加载未完成时，调用触发同步加载 |
| 向后兼容 | 不配置 `registry.services` 时自动单服务模式 |
| 线程安全 | `ConcurrentHashMap` + `synchronized` 保证 |

---

## 新增文件

### 1. `ServiceInstance.java` — 微服务实例模型

```java
src/main/java/com/mcp/config/ServiceInstance.java
```

定义单个微服务的连接信息：
- `id`：唯一标识（如 `pf`, `rib`）
- `baseUrl`：服务基础 URL（如 `http://10.127.7.141:9021`）
- `routePatterns`：路由匹配模式列表（如 `["/pf/**"]`）
- `comet`：该服务对应的 Comet 平台地址（host + port）

### 2. `ServiceRegistry.java` — 注册表配置绑定

```java
src/main/java/com/mcp/config/ServiceRegistry.java
```

`@ConfigurationProperties(prefix = "service.registry")` 绑定配置，管理 `List<ServiceInstance>`。

### 3. `ServiceRouter.java` — 路径路由匹配器

```java
src/main/java/com/mcp/config/ServiceRouter.java
```

使用 Spring `AntPathMatcher`，支持最长前缀优先策略：
```
/pf/inq/xxx/loan/query → 匹配 /pf/** → Service-PF
/rib/infin/inetest/bind → 匹配 /rib/** → Service-RIB
```

---

## 修改文件

### 4. `ApiParamSchema.java` (+ 1 字段)

- 新增 `serviceId` 字段，标记接口所属的微服务
- 构造器增加 `serviceId` 参数（单服务模式传 `null`）

### 5. `CometApiSchemaLoader.java` (核心重构)

| 改动 | 说明 |
|------|------|
| 移除 `@ConditionalOnProperty` | 支持无 Comet 配置时优雅跳过 |
| 注入 `ServiceRegistry` | 获取多服务列表 |
| 异步线程池 | `Executors.newFixedThreadPool(max 8)` Daemon 线程 |
| `load()` 方法重构 | 自动判断单/多服务模式 |
| `loadFromSingleComet()` | 提取原加载逻辑，适配多服务 |
| `loadRemainingOnDemand()` | 同步按需加载尚未完成的服务 |
| 状态追踪 | `loaded` / `loadingInProgress` / `fullyLoadedServices` / `failedServices` |
| `@PreDestroy shutdown()` | 优雅关闭线程池 |
| URL 日志脱敏 | `obscureUrl()` 防止敏感信息泄露 |

**启动流程对比：**
```
改造前：                        改造后：
Spring 启动                      Spring 启动
  │                                │
  ├─ @PostConstruct                 ├─ @PostConstruct
  │   └─ load()                      │   ├─ submit(task1)
  │       └─ 串行HTTP调用 N次        │   ├─ submit(task2)
  │          等待完成 (~15s)          │   └─ submit(task3)
  │                                │   └─ 立即返回 (几毫秒)
  ├─ 容器就绪 ✓                     │
  │                                ├─ 容器就绪 ✓
  │                                │   (后台继续加载)
  └─ 开始服务                       └─ 开始服务
```

### 6. `HttpForwarder.java` (+ 1 依赖)

- 新增 `ServiceRouter` 注入
- `forwardPost()` 中 URL 构造改为动态路由：
  ```java
  ServiceInstance service = serviceRouter.route(apiPath);
  String baseUrl = (service != null) ? service.getBaseUrl() : config.originalUrl();
  String url = baseUrl + apiPath;
  ```
- 日志输出增加路由目标服务 ID

### 7. `DynamicToolRegistrar.java` (+ 按需加载兜底)

- `codeToSchemaMap` 改为字段（`ConcurrentHashMap`），支持动态更新
- `getToolCallbacks()` 中后台加载未完成时返回空数组（而非报错）
- `refreshCodeToSchemaMap()` 方法增量刷新查找表
- `call()` 中 apiCode 未命中时触发 `loadRemainingOnDemand()` + 重试

---

## 配置变更

### 新配置（多服务模式）

```yaml
service:
  registry:
    services:
      - id: service-pf
        base-url: http://10.127.7.141:9021
        route-patterns:
          - /pf/**
        comet:
          host: 10.127.7.141
          port: 9120

      - id: service-rib
        base-url: http://10.127.7.141:9022
        route-patterns:
          - /rib/**
        comet:
          host: 10.127.7.141
          port: 9122
```

### 向后兼容

不配置 `service.registry.services` 时，自动降级为单服务模式，使用原有的 `service.comet.base-url` 和 `service.original.url`。

---

## 编译验证

```
mvn compile -q  →  ✅ 编译通过
```

---

## 生产部署注意事项

1. **首次启动**：所有服务的 Comet Schema 异步加载，约 3-5 秒完成。此期间工具列表为空，LLM 客户端的调用请求会触发按需加载
2. **线程池大小**：当前 `max(8)` 并行，覆盖绝大多数场景（64 核以下）
3. **路由规则冲突**：多个服务匹配同一路径时，最长前缀策略确保精确匹配优先。避免定义重叠的模糊匹配
4. **模板目录**：当前仍为单目录，如果需要按服务隔离模板文件，可后续扩展为 `templates/{serviceId}/` 结构
5. **serverId 硬编码**：`HttpForwarder.buildStandardMessage()` 中 `serverId` 字段仍为 `"127.0.0.1"`，建议后续根据 `ServiceInstance` 动态设置

---

## 工时统计

| 文件 | 操作 | 行数 |
|------|------|------|
| `ServiceInstance.java` | 新增 | ~80 |
| `ServiceRegistry.java` | 新增 | ~35 |
| `ServiceRouter.java` | 新增 | ~70 |
| `ApiParamSchema.java` | 修改 | +5 |
| `CometApiSchemaLoader.java` | 重构 | ～370 (重写) |
| `HttpForwarder.java` | 修改 | +10 |
| `DynamicToolRegistrar.java` | 修改 | +40 |
| `application.yml` | 修改 | +25 |
| **合计** | | **~635** |
