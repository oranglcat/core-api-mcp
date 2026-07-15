# MCP Adapter 接口过滤优化方案

## Context

**问题**：MCP Adapter（`mcp-adapter`）每次启动时通过 `CometApiSchemaLoader` 从 Comet 平台加载全部 ~1000 个接口的完整 Schema（含入参/出参定义）。但 `business-api` Skill 只需要其中几十个核心接口。这导致：

- **启动慢**：需要 ~2000+ 次 HTTP 调用（1 次分页获取列表 + 每个接口 2 次获取入参/出参）
- **Token 浪费**：`invokeBusinessApi` 工具的 description 列出全部 1000 个接口，LLM 每次都要处理
- **内存浪费**：1000 个 ApiParamSchema 对象常驻内存

用户确认核心接口是固定的（约几十个），且所有方面都需要优化。

## 方案对比

### 方案 A：API 白名单过滤 ★ 推荐

在 MCP Adapter 的 `application.yml` 中配置白名单规则，启动时只过滤加载指定接口的完整 Schema。

```yaml
service:
  comet:
    api-filter:
      enabled: true
      # 按 className 前缀过滤（推荐 — 最稳定）
      include-class-prefixes:
        - core1200109445   # 客户信息
        - core1200109446   # 账户查询
        - core1200109456   # 贷款业务
      # 按 URL 路径模式过滤
      include-path-patterns:
        - /pf/inq/**
        - /rib/**
```

**优点**：实现简单，启动快，Token 和内存节约显著
**缺点**：配置需随业务新增接口同步更新（但核心接口固定，更新频率低）

### 方案 B：懒加载模式

启动时只获取接口列表（轻量——仅名称/URL/remark，不含参数 Schema），工具 description 只展示接口名称列表。首次调用具体接口时，按需加载该接口的完整入参/出参 Schema。

**优点**：无需配置，自动适应
**缺点**：首调用延迟（需要等 Schema 加载），实现复杂度高（需改造加载流程+工具执行流程），搜索质量下降（search 依赖入参描述）

### 方案 C：混合方案 = A + B 兜底

白名单接口启动时加载完整 Schema，非白名单接口在运行时按需加载（备用于生产问题的排查场景）。

**优点**：兼顾效率和灵活性
**缺点**：实现较复杂，需要处理两套加载逻辑

---

## 推荐：方案 A + 后续可扩展为 C

理由：
- 核心接口固定，白名单稳定
- 实现量小（改 2-3 个文件）
- 效果好（加载接口数：1000 → ~50，降 95%）
- 后续可无缝升级为方案 C

## 改动清单

### 1. 新增 `CometApiFilter` 配置类

**文件**：`mcp-adapter/src/main/java/com/mcp/config/CometApiFilter.java`

```java
@Configuration
@ConfigurationProperties(prefix = "service.comet.api-filter")
public class CometApiFilter {
    private boolean enabled = false;
    private List<String> includeClassPrefixes = List.of();
    private List<String> includePathPatterns = List.of();
    
    public boolean matches(ApiParamSchema schema) { ... }  // AntPathMatcher 匹配
}
```

### 2. 改造 `CometApiSchemaLoader`

**文件**：`mcp-adapter/src/main/java/com/mcp/scanner/CometApiSchemaLoader.java`

改动点：
- 注入 `CometApiFilter`
- 在 `loadFromSingleComet()` 中，获取接口列表后，先过白名单过滤
- 只有匹配的接口才去获取入参/出参定义
- 非匹配接口只保留元信息（name/url/remark/className/methodName），不获取字段 Schema

### 3. 改造 `DynamicToolRegistrar`

**文件**：`mcp-adapter/src/main/java/com/mcp/tool/DynamicToolRegistrar.java`

改动点：
- `buildUnifiedDescription()`：只列出白名单接口（精简）
- `searchApis()`：搜索范围覆盖所有接口（含非白名单接口的元信息），确保能搜索到
- `buildUnifiedInputSchema()`：apiCode 的 enum 列表包含全量（含非白名单），保持发现能力

### 4. 配置更新

**文件**：`mcp-adapter/src/main/resources/application.yml`

新增 `service.comet.api-filter` 配置段。

### 不改动的文件

| 文件 | 原因 |
|------|------|
| `McpBusinessApiConfig.java` | MCP 客户端无需改动 |
| `AgentConfig.java` | Agent 侧无感知 |
| `SKILL.md` | Skill 定义不变 |
| `HttpForwarder.java` | HTTP 转发逻辑不变 |

## 验证方式

1. 启动 mcp-adapter，观察日志：应只显示 ~50 个接口加载，而非 1000 个
2. 检查 `GET /mcp` 的 SSE 端点，`tools/list` 返回的 description 应只列出白名单接口
3. 测试搜索：`searchBusinessApi("贷款")` 应仍能搜索到非白名单接口
4. 测试调用：白名单内的接口应正常工作
5. 测试非白名单：直接 `invokeBusinessApi(apiCode=非白名单)` 应返回友好的错误提示（兜底方案中可按需加载）
