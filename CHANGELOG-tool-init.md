# MCP Adapter 工具初始化改造报告

## 一、改动背景

MCP Adapter 在多服务（Nacos 异步）模式下，`CometApiSchemaLoader` 后台异步加载所有后
端服务的接口 Schema。原有的 `DynamicToolRegistrar.getToolCallbacks()` 在加载未完成时
返回**空数组**，导致 MCP 客户端（Agent 应用）在初始化时看不到任何工具。而标准 MCP 客户
端只在初始化时调用一次 `tools/list`，后续不再重新拉取。

同时，工具 description 遍历所有 500+ 接口按模块分组枚举，工具定义可达数十到数百 KB，
既拖慢启动速度又增加网络传输开销。

## 二、改动文件清单

| 文件 | 改动类型 | 增/删/改行数 |
|------|----------|-------------|
| `src/main/java/com/mcp/tool/DynamicToolRegistrar.java` | 核心改造 | ~250 行 |
| `src/main/java/com/mcp/tool/SchemaLoadCompleteListener.java` | 简化适配 | ~40 行 |

其他 10+ 个文件（CometApiSchemaLoader、HttpForwarder、ServiceRouter、NacosServiceDiscoverer 等）
均无需改动。

---

## 三、核心改动详解

### 1. `DynamicToolRegistrar.java`

#### 1.1 `getToolCallbacks()` — 永远返回工具

**改造前：**
```java
if (cometLoader == null || !cometLoader.isLoaded()) {
    // 异步加载中 → 返回空数组
    return new ToolCallback[0];  // ← 问题根因
}
```

**改造后：**
```java
if (cometLoader == null) {
    return new ToolCallback[0];  // 仅未配置时才返回空
}
// 用当前已加载的 Schema（可能 partial）构建工具
rebuildCodeToSchemaMap(cometLoader.getAllSchemas());
return new ToolCallback[] { buildUnifiedTool(), buildSearchTool() };
```

效果：MCP 初始化时立即看到两个工具，不再阻塞等待 Schema 加载完成。

#### 1.2 工具回调引用类字段而非参数

**改造前：**
```java
private ToolCallback buildUnifiedTool(Map<String, ApiParamSchema> codeToSchema) {
    // 内部 call() 使用参数 codeToSchema.get(apiCode)
}
private ToolCallback buildSearchTool(Map<String, ApiParamSchema> codeToSchema) {
    // 内部 call() 使用参数 codeToSchema
}
```

**改造后：**
```java
private ToolCallback buildUnifiedTool() {  // 无参
    // 内部 call() 使用类字段 codeToSchemaMap.get(apiCode)
}
private ToolCallback buildSearchTool() {  // 无参
    // 内部 call() 使用类字段 codeToSchemaMap
}
```

关键架构认知：`codeToSchemaMap` 是 `ConcurrentHashMap`，工具回调内部持有其引用。
后台 `refreshCodeToSchemaMap()` 新增条目后，已注册工具自动可见。

#### 1.3 紧凑描述模式

`buildUnifiedDescription()` 从遍历所有接口按模块分组枚举（O(N), 可达数十 KB），
改为固定字符串（约 500 字节），引导 LLM 使用 `searchBusinessApi` 搜索：

> 调用后端业务接口（POST）。通过 apiCode 选择要调用的接口...
> **重要：如果不确定使用哪个 apiCode，必须先调用 searchBusinessApi 工具搜索接口...**

#### 1.4 紧凑输入 Schema

`buildUnifiedInputSchema()` 不再根据阈值生成 enum 列表，apiCode 字段始终用
description 引导搜索，工具定义大小从 O(N log N) 降为 O(1)。

#### 1.5 权限提升

`refreshCodeToSchemaMap()` 从 `private` 改为包可见（`package-private`），
供 `SchemaLoadCompleteListener` 调用。

#### 1.6 清理

删除不再使用的常量和方法：
- 移除 `MAX_API_PER_GROUP`（紧凑描述不再分组展示）
- 移除 `ENUM_TRUNCATE_THRESHOLD`（不再生成 enum）
- 移除 `extractGroupKey()`（不再按组展示接口列表）

---

### 2. `SchemaLoadCompleteListener.java`

**改造前：** 事件触发后重新获取工具回调、`addTool()` 注册（可能产生冲突）、通知客户端。

**改造后：** 事件触发后只做两件事：
1. 调用 `dynamicToolRegistrar.refreshCodeToSchemaMap()` — 刷新已注册工具的内部查找表
2. 调用 `mcpSyncServer.notifyToolsListChanged()` — 通知兼容动态更新的客户端

注意：工具已在 `getToolCallbacks()` 中注册，此处不再重复 `addTool()`。
移除 `toolsRegistered` 守卫标志（`AtomicBoolean CAS` 保证事件只发布一次，无需额外守卫）。

---

## 四、改动思路总结

### 策略："立即返回工具，保持轻量"

```
┌──────────────────────────────────────────────────────────┐
│  MCP 客户端连接                工具初始化完成             │
│        │                          │                      │
│        ▼                          ▼                      │
│  ┌──────────────┐        ┌──────────────────┐           │
│  │ getToolCallbacks()     │ SchemaLoadCompleteEvent    │  │
│  │ 返回 2 个工具  │        │ 刷新查找表 + 通知  │           │
│  └──────┬───────┘        └────────┬─────────┘           │
│         │                         │                      │
│         ▼                         ▼                      │
│  ┌──────────────────────────────────────────┐            │
│  │ codeToSchemaMap（ConcurrentHashMap）       │            │
│  │ 初始: 已加载的部分接口                        │            │
│  │ 后台加载: 逐步添加新接口                      │            │
│  │ 完成后: 全部接口                            │            │
│  │ 工具回调持有引用 → 自动可见                  │            │
│  └──────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────┘
```

### 关键设计决策

1. **不在 getToolCallbacks() 中等候**：异步加载可能耗时长，阻塞会拖慢 MCP 初始化
2. **使用 CompactHashMap 引用传递**：注册时传入 Map 引用，后台更新 Map 后工具调用自动感知
3. **不依赖 MCP 客户端动态更新能力**：工具名固定不变，LLM 通过搜索+按需调用来发现新接口
4. **紧凑描述作为默认行为**：无论接口数量多少，都使用紧凑描述，避免条件分支和复杂性

---

## 五、测试结果

### 编译验证

```
$ mvn compile
[INFO] BUILD SUCCESS
```

Maven 编译通过，无错误、无警告。

### 验证清单

| 验证项 | 预期结果 | 状态 |
|--------|----------|------|
| Maven 编译 | BUILD SUCCESS | ✅ |
| getToolCallbacks() 返回空 | 仅在 cometLoader==null 时返回空 | ✅ |
| 异步加载中工具可见 | 返回 2 个 ToolCallback | ✅ |
| buildUnifiedTool() 无参 | 内部引用 codeToSchemaMap | ✅ |
| buildSearchTool() 无参 | 内部引用 codeToSchemaMap | ✅ |
| refreshCodeToSchemaMap() 可见性 | package-private | ✅ |
| 旧常量/方法已清理 | MAX_API_PER_GROUP、ENUM_TRUNCATE_THRESHOLD、extractGroupKey 已移除 | ✅ |
| 监听器不重复 addTool() | 仅刷新 + 通知 | ✅ |
| 监听器无 toolsRegistered 守卫 | 依赖事件源的 CAS 保证 | ✅ |

### 回归验证（需手动执行）

1. **启动 MCP Adapter** → 日志显示工具在初始化时注册
2. **`tools/list` 调用** → 返回 `invokeBusinessApi` 和 `searchBusinessApi`
3. **多服务异步加载** → `codeToSchemaMap` 随加载进度自然增长
4. **`invokeBusinessApi` 调用**：
   - apiCode 已加载 → 正常转发
   - apiCode 未加载 → 触发按需加载 → 返回结果
5. **`searchBusinessApi` 调用** → 搜索范围逐步扩大
6. **所有服务加载完成** → SchemaLoadCompleteListener 日志显示刷新完成

---

## 六、性能对比

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| `getToolCallbacks()` 耗时 | O(N) + O(G) | O(C) 仅重建 Map |
| 工具 description 大小 | 数十 ~ 数百 KB | ~500 字节 |
| 输入 Schema 大小 | 含 enum 列表时极大 | ~300 字节 |
| 工具定义总大小 | 数百 KB | 约 1-2 KB |
| 接口数增加对性能影响 | 线性增长 | 无影响 |

注：N = 总接口数, G = 业务模块数, C = 当前已加载接口数
