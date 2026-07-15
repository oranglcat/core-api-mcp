# MCP Adapter — 动态 REST → MCP 适配层

## 项目概述

**MCP Adapter** 是一个基于 **Spring Boot 3.4 + Spring AI MCP 1.1** 构建的服务适配层。它作为"桥梁"，将已有的 Spring Boot REST 服务自动注册为 **MCP（Model Context Protocol）Tool**，使 Claude 等大语言模型能够通过 MCP 协议直接调用业务接口，而**后端原服务无需任何修改**。

## 目录

- [设计思路](#设计思路)
- [实现思路](#实现思路)
- [工作流程](#工作流程)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [项目结构](#项目结构)

---

## 设计思路

### 1. 零侵入适配

后端已有大量稳定的 REST 接口，重写或改造这些接口以适应 AI 调用成本高昂、风险大。MCP Adapter 的设计目标是**作为一个独立的代理层**，位于 LLM 和后端业务微服务集群之间，以"旁路"方式工作——对后端零修改，对 LLM 提供标准 MCP 接口。

```
┌──────────┐     MCP(SSE)     ┌──────────────┐                    ┌──────────────────┐
│  Claude   │ ◄──────────────► │ MCP Adapter  │ ─── 动态路由 ────► │  微服务集群       │
│  (LLM)    │                  │  (本服务)     │                   │                  │
└──────────┘                   │              │                   │  ├─ Service-A    │
                              │  ├─ Router   │ ──► /pf/** ──────►│  │  (:9021)      │
                              │  ├─ Loader   │                   │  │  Comet(:9120) │
                              │  │ (并行异步) │                   │  ├─ Service-B    │
                              │  └─ Forwarder│ ──► /rib/** ─────►│  │  (:9022)      │
                              └──────────────┘    JSON 响应      │  │  Comet(:9121) │
                                                                 └──────────────────┘
```

### 2. 统一 Tool 而非多 Tool

最初的想法是为每个后端接口注册一个独立的 MCP Tool。但实际使用中发现这会导致严重问题：

- LLM 在数十甚至上百个 Tool 中难以选择，频繁选错接口
- LLM 会盲目试错，先随意选一个 Tool 发出请求，得到错误后再试另一个
- 大量 Tool 的 description 和 schema 消耗大量 tokens

因此设计为 **"单一统一 Tool"（`invokeBusinessApi`）**，通过 `apiCode` 参数选择接口，配合 `params` 传入业务参数。LLM 只需理解一个 Tool 的用法，通过 description 中的接口列表选择正确的 apiCode。

```
invokeBusinessApi(apiCode, params)
  ├─ apiCode: "core1200109445_runService"  → POST /pf/xxx/loan/query
  ├─ apiCode: "core1200109456_queryService" → POST /pf/xxx/account/balance
  └─ apiCode: "core1200109467_syncService"  → POST /pf/xxx/trade/sync
```

### 3. 动态 Schema 加载

接口参数定义不硬编码在代码中，而是从 **Comet 平台**（内部接口文档管理系统）动态加载。这意味着：

- **新增接口无需改代码**：开发者在 Comet 平台注册接口后，重启适配器即可生效
- **参数 Schema 实时同步**：入参/出参定义直接来自 Comet，与后端保持同步
- **减少人工维护成本**：无需在适配器中重复维护一份参数定义

### 4. 标准报文封装（金融业务强制要求）

后端金融业务系统要求每一个请求都必须遵循固定的标准报文格式，报文分为 4 个顶层节点：

| 节点 | 说明 | 来源 |
|------|------|------|
| `sysHead` | 系统公共头：服务编码、交易码、流水号、机构号等 | 模板 / 全局配置 + 运行时生成 |
| `appHead` | 应用业务头：分页信息（页码、总数等） | 模板 / 全局配置 |
| `localHead` | 本地渠道响应头（仅响应报文中出现） | 后端返回 |
| `body` | 业务数据体（随接口变化） | LLM 传入的 params 动态填充 |

适配器因此将报文封装设计为**强制环节**——每次向后端转发请求前，必须将参数嵌套进标准报文结构中。封装策略如下：

- **per-API 模板优先**：每个接口可在 `templates/` 下放独立模板文件，支持 `${seqNo}`、`${tranDate}` 等运行时占位符
- **全局兜底配置**：没有 per-API 模板时，使用 application.yml 中 `service.message-format.sys-head / app-head` 配置的头字段
- **body 动态填充**：LLM 传入的业务参数始终填入 body 节点
- **自动生成运行时字段**：seqNo（全局唯一流水号）、subSeqNo、tranDate、tranTimestamp 在每次调用时自动生成

### 5. "搜索 + 调用"双 Tool 模式

单一统一 Tool 解决了多 Tool 选择困难的问题，但 LLM 面对成百上千的 apiCode 仍然容易选错。为此设计了**双 Tool 协作模式**：

| 阶段 | Tool | 职责 |
|------|------|------|
| **搜索** | `searchBusinessApi(keywords)` | 根据关键词在 apiName、remark、入参等多字段中搜索，返回匹配的 apiCode 和详情 |
| **调用** | `invokeBusinessApi(apiCode, params)` | 执行实际的业务接口调用 |

LLM 的决策流程变为：**不确定时先搜索 → 找到准确 apiCode → 再调用**，大幅降低盲目试错的概率。

同时，`invokeBusinessApi` 的 description 做了三项强化以提升选择准确度：

- **展示 remark（中文业务描述）**：每行接口列出业务说明，帮助 LLM 快速理解接口用途
- **展示必填入参摘要**：每个接口下方显示字段名和中文描述，帮助 LLM 确认是否匹配当前问题
- **按 URL 业务路径分组**：从 URL 中提取有业务含义的单词作为组名（如 `loan`、`account`），替代原来无意义的 className 前缀

---

## 实现思路

### 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 3.4.4 | 应用框架 |
| Spring AI MCP 1.1.6 | MCP 服务器（SSE 传输） |
| Java 17 | 运行环境 |
| Spring Web (RestTemplate) | 向后端转发 HTTP 请求 |
| Comet REST API | 获取接口参数定义 |

### 核心模块

#### 1. 配置层（config 包）

- **`AppConfig`**：管理后端服务的连接信息（URL、超时、认证），构造带认证拦截器的 `RestTemplate` Bean（单服务模式兜底）
- **`CometConfig`**：管理 Comet 平台的连接信息（base-url、超时、请求头）（单服务模式兜底）
- **`MessageFormatConfig`**：管理标准报文格式配置（是否启用、模板目录、sysHead/appHead 兜底字段）
- **`MessageTemplateLoader`**：应用启动时从模板目录加载所有 JSON 模板文件，支持扁平命名和子目录结构两种组织方式
- **`ServiceRegistry`**：微服务注册表，绑定 `application.yml` 中 `service.registry.services` 列表配置
- **`ServiceInstance`**：单个微服务实例模型，包含 id、baseUrl、routePatterns、comet 地址
- **`ServiceRouter`**：服务路由器，使用 AntPathMatcher 根据 API 路径前缀匹配目标微服务（最长前缀优先）

#### 2. Schema 层（scanner 包）

- **`CometApiSchemaLoader`**：接口参数 Schema 加载器。支持两种模式：
  - **多服务模式**（新）：通过 `ServiceRegistry` 获取所有后端微服务的 Comet 地址，以**异步并行**方式加载各服务的接口定义，不阻塞应用启动。加载完毕后发布 `SchemaLoadCompleteEvent` 触发动态工具注册
  - **单服务模式**（兼容）：通过 `service.comet.base-url` 配置单个 Comet 地址，同步加载
  - **按需加载**：后台加载未完成时，调用触发同步加载兜底
- **`ApiParamSchema`**：单个接口的完整元信息（所属 serviceId、URL、名称、服务端 ID、类名、方法名、入参列表、出参列表）
- **`FieldDef`**：字段定义（名称、类型、是否必输、长度、取值范围、嵌套子字段），提供 `schemaType()` 方法将 Comet 字段类型映射为 JSON Schema 类型

#### 3. Tool 层（tool 包）

- **`DynamicToolRegistrar`**：实现 Spring AI 的 `ToolCallbackProvider` 接口，在 MCP Server 初始化时被调用。核心逻辑：
  1. 检查 Comet Schema 是否已加载完成
     - **多服务异步模式**：后台加载未完成 → 返回空工具列表，等待后续事件驱动动态注册
     - **单服务同步模式**：已加载完成 → 正常构建并返回工具
  2. 构建 `apiCode → ApiParamSchema` 查找表（apiCode 格式：`{类名}_{方法名}`）
  3. 注册两个 Tool：
     - **`invokeBusinessApi`** — 统一业务接口调用。description 按业务模块分组展示接口，每条含 apiCode、中文名、业务描述（remark）和入参摘要；响应末尾追加止语引导，防止 LLM 继续调用其他接口
     - **`searchBusinessApi`** — 接口搜索。在 apiName、remark、className、methodName、入参名和描述中多字段搜索，评分排序后返回匹配接口详情
- **`SchemaLoadCompleteListener`**：监听 `SchemaLoadCompleteEvent`，在多服务异步加载完成后将工具动态注册到 `McpSyncServer`，并通知已连接客户端刷新工具列表。从 `DynamicToolRegistrar` 中拆分独立，避免循环依赖
- **`HttpForwarder`**：执行实际 HTTP 转发。处理流程：
  1. 校验必输字段
  2. **动态路由**：通过 `ServiceRouter` 根据 API 路径匹配目标微服务（如 `/pf/**` → Service-PF）
  3. 拼接完整 URL（`目标服务 baseUrl + apiPath`）
  4. 从模板加载报文头，封装为标准报文格式（强制执行）
  5. 发送 POST 请求
  6. 美化 JSON 响应，末尾追加"调用成功，直接回答用户，不要继续调用其他接口"的止语引导
  7. 异常转译为友好的中文消息

### 启动流程

```
1. Spring Boot 启动
2. @PostConstruct → MessageTemplateLoader.loadAll()
   └─ 扫描 templates/ 目录加载所有 .json 模板
3. @PostConstruct → CometApiSchemaLoader.load()
   ├─ 多服务模式：异步提交所有服务的加载任务，立即返回
   │  ├─ submit(Service-A: Comet :9120) → 后台执行
   │  ├─ submit(Service-B: Comet :9121) → 后台执行
   │  └─ (启动不阻塞，几毫秒返回)
   └─ 单服务模式（兼容）：串行加载，等待完成
4. Spring AI MCP Server 初始化
   └─ DynamicToolRegistrar.getToolCallbacks()  [🐛 仅同步模式在此注册]
      ├─ 多服务异步：返回空，不阻塞启动
      └─ 单服务同步：构建工具列表并注册

5. [多服务异步] 后台加载完毕
   └─ CometApiSchemaLoader 发布 SchemaLoadCompleteEvent
      └─ SchemaLoadCompleteListener 接收事件
         ├─ 调用 getToolCallbacks() 获取已构建的工具
         ├─ McpToolUtils.toSyncToolSpecifications() 转换
         ├─ McpSyncServer.addTool() 动态注册
         └─ notifyToolsListChanged() 通知客户端刷新

6. MCP Server 启动在 /mcp 端点（SSE 传输）
```

---

## 工作流程

### 完整调用链路

```
Claude (LLM)                 MCP Adapter                    后端业务服务
    │                            │                              │
    │  1. MCP 连接初始化          │                              │
    │──────────────────────────► │                              │
    │                            │                              │
    │  2. 获取 Tool 列表          │                              │
    │──────────────────────────► │                              │
    │◄──── invokeBusinessApi ────│                              │
    │                            │                              │
    │  3. 调用 Tool               │                              │
    │  (apiCode + params)        │                              │
    │──────────────────────────► │                              │
    │                            │  4. 参数校验                  │
    │                            │  5. 报文模板匹配               │
    │                            │  6. 组装标准报文               │
    │                            │  7. HTTP POST 转发            │
    │                            │─────────────────────────────►│
    │                            │                              │
    │                            │  8. 响应处理 + 美化           │
    │◄────────────────────────── │                              │
```

---

### 一、MCP 连接与 Tool 发现

#### Step 1.1: MCP SSE 握手

LLM（Claude）通过 **SSE（Server-Sent Events）** 协议连接适配器的 MCP 端点：

```
LLM → GET http://localhost:8124/mcp  (SSE 连接建立)
LLM ← 服务端推送 endpoint 信息（用于后续 JSON-RPC 通信）
```

Spring AI MCP Server 自动完成握手，无需额外代码。

#### Step 1.2: LLM 请求 Tool 列表

LLM 通过 `tools/list` 请求获取可用工具。适配器返回两个 Tool：

**① `invokeBusinessApi`** — 业务接口调用工具，description 按业务模块分组展示接口列表，每条包含 apiCode、中文名、业务描述和入参摘要：

```json
{
  "name": "invokeBusinessApi",
  "description": "调用后端业务接口（POST）。通过 apiCode 选择要调用的接口... 不确定时先调用 searchBusinessApi 搜索",
  "inputSchema": {
    "type": "object",
    "properties": {
      "apiCode": {
        "type": "string",
        "description": "接口编码，格式：{业务类名}_{方法名}",
        "enum": ["core1200109445_runService", "core1200109456_queryService", ...]
      },
      "params": {
        "type": "object",
        "description": "接口业务参数（即报文 body 内容）",
        "additionalProperties": true
      }
    },
    "required": ["apiCode"]
  }
}
```

**② `searchBusinessApi`** — 接口搜索工具，帮助 LLM 在不确定时查找准确的 apiCode：

```json
{
  "name": "searchBusinessApi",
  "description": "搜索后端业务接口。根据关键词查找匹配的接口，返回接口编码(apiCode)和详细参数信息...",
  "inputSchema": {
    "type": "object",
    "properties": {
      "keywords": {
        "type": "string",
        "description": "搜索关键词，支持空格分隔，如：贷款 查询"
      }
    },
    "required": ["keywords"]
  }
}
```

LLM 的决策流程：**不确定 apiCode 时先调用 `searchBusinessApi` 搜索 → 找到准确结果后再调用 `invokeBusinessApi`。**

---

### 二、LLM 调用 Tool（参数传入）

#### Step 2.1: LLM 决策并构造参数

LLM 根据用户的问题，**优先考虑是否需要先搜索**：

- 如果对 apiCode 有明确把握 → 直接构造参数调用 `invokeBusinessApi`
- 如果不确定 → 先调用 `searchBusinessApi(keywords)` 搜索，从搜索结果中获取准确的 apiCode、入参说明后，再调用 `invokeBusinessApi`

**用户提问示例：**
> "查询客户 351002 的贷款账户 6222021234567890 的余额"

**LLM 的典型决策路径：**
```
1. searchBusinessApi("贷款 查询")
   → 返回: core1200109456_queryService (贷款查询)
     必填参数: branchId(机构号), accountNo(账号)
2. invokeBusinessApi("core1200109456_queryService", {...})
```

**LLM 的 MCP Tool 调用（JSON-RPC）：**

```json
{
  "method": "tools/call",
  "params": {
    "name": "invokeBusinessApi",
    "arguments": {
      "apiCode": "core1200109456_queryService",
      "params": {
        "branchId": "351002",
        "accountNo": "6222021234567890",
        "ccy": "CNY"
      }
    }
  }
}
```

#### Step 2.2: DynamicToolRegistrar 接收参数

`DynamicToolRegistrar` 的 `call(String argumentJson)` 方法被 Spring AI MCP 框架调用，接收 JSON 字符串参数：

```java
public String call(String argumentJson) {
    // 1. 反序列化 JSON 字符串为 Map
    Map<String, Object> args = objectMapper.readValue(argumentJson, Map.class);

    // 2. 提取 apiCode
    String apiCode = (String) args.get("apiCode");

    // 3. 提取业务参数 params
    Map<String, Object> params = (Map<String, Object>) args.get("params");
    if (params == null) params = Map.of();

    // 4. 查找 schema（apiCode → URL 映射）
    ApiParamSchema schema = codeToSchema.get(apiCode);
    if (schema == null && cometLoader.isLoadingInProgress()) {
        // 4a. ★ 后台加载未完成 → 触发按需同步加载
        cometLoader.loadRemainingOnDemand();
        refreshCodeToSchemaMap();   // 增量刷新查找表
        schema = codeToSchema.get(apiCode);
    }

    // 5. 从 schema 中提取必输字段列表
    List<String> requiredFields = schema.inputs().stream()
        .filter(FieldDef::required)
        .map(FieldDef::name)
        .toList();

    // 6. 调用 HttpForwarder 转发（动态路由到目标微服务）
    return httpForwarder.forwardPost(schema.url(), params, requiredFields);
}
```

#### Step 2.3: 参数数据流全景

```
LLM 调用
  │
  ▼  JSON-RPC over SSE
argumentJson (原始 JSON 字符串)
  │
  ▼  objectMapper.readValue()
Map<String, Object> args
  ├── apiCode: "core1200109456_queryService"
  └── params: { branchId, accountNo, ccy }
       │
       ▼  传入 HttpForwarder.forwardPost()
       Map<String, Object> params (业务参数，待填入 body)
```

---

### 三、参数校验

#### Step 3.1: 必输字段检查

`HttpForwarder.forwardPost()` 接收 `requiredFields` 列表后，逐字段检查 `params` 中是否包含：

```java
List<String> missing = new ArrayList<>();
for (String field : requiredFields) {
    if (params == null || !params.containsKey(field) || params.get(field) == null) {
        missing.add(field);
    }
}
if (!missing.isEmpty()) {
    return "错误: 缺少必输参数 [branchId, accountNo]。请提供这些参数后重试。";
}
```

**校验结果示例：**

| 场景 | requiredFields | params 包含 | 结果 |
|------|---------------|-------------|------|
| 参数完整 | [branchId, accountNo] | {branchId, accountNo, ccy} | ✅ 通过 |
| 缺少字段 | [branchId, accountNo] | {branchId} | ❌ 返回提示，LLM 自动补充后重试 |
| 无需参数 | [] 或 null | 任意或空 | ✅ 跳过校验 |

> **如果缺少必输字段，LLM 收到错误提示后会补充参数重新调用**——这是统一 Tool 设计的关键好处：LLM 在同一 Tool 中自我修正，而不是切换 Tool。

---

### 四、标准报文组装（核心强制环节）

**这是强制执行的流程，每一次请求都必须经过报文封装。** 组装由 `HttpForwarder.buildStandardMessage()` 完成。

#### Step 4.1: 运行时字段预生成

在组装前，先计算动态值，确保一次调用中的所有时间相关字段使用同一时间点：

```java
LocalDateTime now = LocalDateTime.now();
Map<String, String> dynamicFields = new LinkedHashMap<>();

dynamicFields.put("seqNo",        "SY250702143022123456");  // 全局唯一流水号
dynamicFields.put("subSeqNo",     "SS250702143022654321");  // 子流水号
dynamicFields.put("tranDate",     "20250702");              // 交易日期 yyyyMMdd
dynamicFields.put("tranTimestamp","143022123");             // 交易时间戳 HHmmssSSS
dynamicFields.put("serverId",     "127.0.0.1");             // 服务端标识
dynamicFields.put("branchId",     "351002");                // 机构号（来自全局配置）
dynamicFields.put("userId",       "QQ");                    // 用户标识（来自全局配置）
```

**seqNo 生成规则：**
```
"SY" + yyMMddHHmmssSSS + 6位随机数
  → "SY" + "250702143022123" + "456789"
  → "SY250702143022123456789"
```

#### Step 4.2: 模板查找（三级 Fallback）

`MessageTemplateLoader.getTemplate(apiPath)` 执行三级查找：

```
查找路径: /pf/inq/xxx/loan/query
              │
              ▼ normalizePath()
         pf_inq_xxx_loan_query
              │
   ┌──────────┼─────────────┐
   ▼          ▼             ▼
  Level 1   Level 2       Level 3
精确匹配    子目录结构     默认模板
templates   templates/    templates/
.get(       pf/inq/xxx/   _default.json
"pf_inq_    loan/query    (全局兜底)
xxx_loan_    .json
query")    存在？
              │
       ┌──────┴──────┐
       ▼             ▼
     存在 → 使用   不存在 → 回到 Level 1 结果
```

**文件组织方式示例：**

```
templates/                              templates/
├── _default.json        ← 全局兜底       ├── _default.json
├── pf_inq_xxx_loan_query.json          └── pf/
    ↑ 扁平命名                               └── inq/
                                              └── xxx/
                                                  └── loan/
                                                      └── query.json  ← 子目录结构
```

#### Step 4.3: per-API 模板加载与占位符解析

**模板文件内容（`pf_inq_xxx_loan_query.json`）：**

```json
{
  "sysHead": {
    "seqNo": "${seqNo}",
    "subSeqNo": "${subSeqNo}",
    "tranDate": "${tranDate}",
    "tranTimestamp": "${tranTimestamp}",
    "branchId": "${branchId}",
    "userId": "${userId}",
    "userLang": "CHINESE",
    "moduleId": "CL",
    "sceneId": "01",
    "serverId": "${serverId}",
    "sourceType": "MT",
    "authFlag": "N"
  },
  "appHead": {
    "currentNum": "0",
    "pageEnd": "0",
    "pageStart": "0",
    "pgupOrPgdn": "0",
    "totalNum": "-1"
  }
}
```

**深层递归解析：** 模板加载后，`deepResolvePlaceholders()` 递归遍历所有层级，将 `${key}` 替换为运行时值：

```java
private Map<String, Object> deepResolvePlaceholders(
        Map<String, Object> template, Map<String, String> dynamicFields) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : template.entrySet()) {
        if (entry.getValue() instanceof String s) {
            // 对每个字符串值执行占位符替换
            result.put(entry.getKey(), resolvePlaceholderString(s, dynamicFields));
        } else if (entry.getValue() instanceof Map m) {
            // 递归处理嵌套 Map（如 sysHead 内部的嵌套结构）
            result.put(entry.getKey(), deepResolvePlaceholders(m, dynamicFields));
        } else {
            result.put(entry.getKey(), entry.getValue());
        }
    }
    return result;
}
```

**替换结果（解析后）：**

```json
{
  "sysHead": {
    "seqNo": "SY250702143022123456",
    "subSeqNo": "SS250702143022654321",
    "tranDate": "20250702",
    "tranTimestamp": "143022123",
    "branchId": "351002",
    ...
  },
  "appHead": { ... }
}
```

> 如果模板中有 `${xxx}` 未在 `dynamicFields` 中找到对应值，会保持原样并记录警告日志。

#### Step 4.4: body 填充

占位符解析完成后，将 LLM 传入的 `params` 写入 body 节点：

```java
Map<String, Object> message = deepResolvePlaceholders(template, dynamicFields);
message.put("body", businessBody);  // 此时 businessBody = {branchId, accountNo, ccy}
```

**最终组装完成的完整报文：**

```json
{
  "sysHead": {
    "seqNo": "SY250702143022123456",
    "subSeqNo": "SS250702143022654321",
    "tranDate": "20250702",
    "tranTimestamp": "143022123",
    "branchId": "351002",
    "userId": "QQ",
    "userLang": "CHINESE",
    "moduleId": "CL",
    "sceneId": "01",
    "serverId": "127.0.0.1",
    "sourceType": "MT",
    "authFlag": "N"
  },
  "appHead": {
    "currentNum": "0",
    "pageEnd": "0",
    "pageStart": "0",
    "pgupOrPgdn": "0",
    "totalNum": "-1"
  },
  "body": {
    "branchId": "351002",
    "accountNo": "6222021234567890",
    "ccy": "CNY"
  }
}
```

#### Step 4.5: 无 per-API 模板时的兜底逻辑

如果三级查找都未命中模板，使用全局配置字段组装最小报文：

```yaml
# application.yml 中的配置
service:
  message-format:
    sys-head:
      branchId: "351002"
      userId: QQ
      userLang: CHINESE
      moduleId: CL
      sceneId: "01"
      sourceType: MT
      authFlag: N
    app-head:
      currentNum: "0"
      pageEnd: "0"
      pageStart: "0"
      pgupOrPgdn: "0"
      totalNum: "-1"
```

兜底报文同样经过动态字段注入：

```java
Map<String, Object> message = new LinkedHashMap<>();
Map<String, String> sysHead = new LinkedHashMap<>(messageFormatConfig.getSysHead());
sysHead.putAll(dynamicFields);  // 动态字段覆盖配置值
message.put("sysHead", sysHead);
message.put("appHead", new LinkedHashMap<>(messageFormatConfig.getAppHead()));
message.put("body", businessBody);
return message;
```

#### Step 4.6: 报文组装全流程总结

```
LLM 传入
params: {branchId, accountNo, ccy}
                    │
                    ▼
┌─── 运行时字段生成 ──────────────────────┐
│  seqNo, subSeqNo, tranDate,           │
│  tranTimestamp, serverId              │
└──────────┬────────────────────────────┘
           │
           ▼
┌─── 模板查找 (三级 Fallback) ──────────┐
│  Level 1: 精确匹配模板文件             │
│  Level 2: 子目录结构模板               │
│  Level 3: _default.json 全局兜底      │
└──────────┬────────────────────────────┘
           │
           ▼
┌─── 占位符解析 ────────────────────────┐
│  ${seqNo} → SY250702143022123456     │
│  ${tranDate} → 20250702              │
│  ${branchId} → 351002                │
└──────────┬────────────────────────────┘
           │
           ▼
┌─── body 填充 ─────────────────────────┐
│  message.put("body", params)          │
└──────────┬────────────────────────────┘
           │
           ▼
┌─── 完整报文 ──────────────────────────┐
│  { sysHead, appHead, body }          │
└──────────────────────────────────────┘
```

---

### 五、HTTP 转发

#### Step 5.1: 动态路由 + 拼接 URL 与发送

```java
// 1. 根据 API 路径动态路由到目标微服务
ServiceInstance service = serviceRouter.route(apiPath);
// 例如: /pf/inq/xxx/loan/query → 匹配 /pf/** → Service-PF

// 2. 拼接完整 URL（路由命中时使用目标服务地址，否则走默认地址）
String baseUrl = (service != null) ? service.getBaseUrl() : config.originalUrl();
String url = baseUrl + apiPath;
// 例如: http://10.127.7.141:9021 + /pf/inq/xxx/loan/query
//     = http://10.127.7.141:9021/pf/inq/xxx/loan/query

// 3. 发送 POST 请求，请求体就是上一步组装好的完整报文
String response = restTemplate.postForObject(url, requestPayload, String.class);
```

#### Step 5.2: 认证拦截（透明注入）

如果配置了认证，`RestTemplate` 的拦截器自动注入认证头，对上层代码完全透明：

```java
// AppConfig 中注册的拦截器
rt.getInterceptors().add((request, body, execution) -> {
    if ("bearer".equalsIgnoreCase(auth.getType())) {
        request.getHeaders().setBearerAuth(auth.getToken());
    } else if ("basic".equalsIgnoreCase(auth.getType())) {
        request.getHeaders().setBasicAuth(auth.getUsername(), auth.getPassword());
    }
    return execution.execute(request, body);
});
```

#### Step 5.3: HTTP 请求全景

```
目标 URL:  POST http://10.127.7.141:9021/pf/inq/xxx/loan/query
路由信息:  /pf/** → Service-PF (baseUrl: http://10.127.7.141:9021)

请求头:
  Content-Type: application/json
  Authorization: Bearer xxxxxx             (如果配置了认证)

请求体 (完整标准报文):
{
  "sysHead": {
    "seqNo": "SY250702143022123456",
    "tranDate": "20250702",
    ...
  },
  "appHead": { ... },
  "body": {
    "branchId": "351002",
    "accountNo": "6222021234567890",
    "ccy": "CNY"
  }
}

响应体 (JSON):
{
  "sysHead": {
    "respCode": "000000",
    "respMsg": "成功"
  },
  "localHead": { ... },
  "body": {
    "accountName": "张三",
    "balance": "125000.00",
    "ccy": "CNY"
  }
}
```

---

### 六、响应处理与返回

#### Step 6.1: JSON 美化 + 止语引导

后端的原始响应经过美化格式化，同时在末尾追加止语引导，防止 LLM 继续调用其他接口进行"探索"：

```java
String prettyJson = prettyPrintJson(response);
return prettyJson + "\n\n---\n接口调用成功。以上是完整返回数据，请直接回答用户，**不要继续调用其他接口**。";
```

#### Step 6.2: 异常转译

将底层异常转换为 LLM 能理解的中文提示，隐藏内部实现细节：

```java
private String resolveUserMessage(Exception e) {
    String msg = e.getMessage();
    if (msg.contains("Connection refused"))
        return "后端服务连接失败（超时或拒绝连接），请确认后端服务已启动。";
    if (msg.contains("400"))
        return "请求参数格式错误，请检查参数后重试。";
    if (msg.contains("401"))
        return "认证失败，请联系管理员。";
    if (msg.contains("404"))
        return "请求的接口不存在，请联系管理员。";
    if (msg.contains("500"))
        return "服务端内部错误，已记录日志，请稍后重试。";
    return "请求处理失败: " + msg;
}
```

#### Step 6.3: LLM 解析并回答

MCP Adapter 返回的 JSON 字符串经过 MCP 协议传回 LLM。LLM 读取返回的 `body` 内容，用自然语言回答用户：

**返回给 LLM 的内容：**
```json
{
  "sysHead": {
    "respCode": "000000",
    "respMsg": "成功"
  },
  "body": {
    "accountName": "张三",
    "balance": "125000.00",
    "ccy": "CNY"
  }
}
```

**LLM 对用户的回答：**
> "客户张三（机构号 351002）的贷款账户 6222021234567890 当前余额为 ¥125,000.00（人民币）。"

---

### 七、一次完整的流程示例（前后对照）

```
用户提问: "查一下 351002 的贷款账户 6222021234567890 余额"
    │
    ▼
LLM 不确定具体 apiCode
    │
    ├─ 先调用 searchBusinessApi("贷款 查询")
    │    ├─ 搜索范围: apiName、remark、入参名和描述
    │    ├─ 评分排序 → 返回匹配结果
    │    └─ 找到: core1200109456_queryService (贷款查询)
    │       入参: branchId(机构号)[必填], accountNo(账号)[必填]
    │
    ├─ 确定 apiCode 后构造 params
    │    params: { branchId: "351002", accountNo: "6222021234567890", ccy: "CNY" }
    │
    ▼
调用 invokeBusinessApi(apiCode, params)
    │
    ├─ 校验必输: [branchId, accountNo] → 全部提供 ✅
    │
    ├─ 运行时字段: seqNo="SY250702143022123456",
    │              tranDate="20250702", serverId="127.0.0.1"
    │
    ├─ 模板匹配: pf_inq_xxx_loan_query.json → 命中 ✅
    │
    ├─ 占位符解析: ${seqNo} → SY250702143022123456, ...
    │
    ├─ body 填充: params → message.body
    │
    ├─ 动态路由: /pf/** → Service-PF
    │
    ├─ 完整报文 POST http://10.127.7.141:9021/pf/inq/xxx/loan/query
    │    {
    │      "sysHead": { "seqNo": "SY250702143022123456", "tranDate": "20250702", ... },
    │      "appHead": { ... },
    │      "body": { "branchId": "351002", "accountNo": "6222021234567890", "ccy": "CNY" }
    │    }
    │
    ├─ 后端响应 json
    │    {
    │      "sysHead": { "respCode": "000000" },
    │      "body": { "accountName": "张三", "balance": "125000.00", "ccy": "CNY" }
    │    }
    │
    ├─ JSON 美化 + 止语引导 → 返回给 LLM
    │
    ▼
LLM 回答: "客户张三（机构号 351002）的贷款账户余额为 ¥125,000.00。"
```

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- 可访问的 Comet 接口文档平台（或准备静态接口列表）
- 可访问的后端业务服务

### 配置修改

编辑 `src/main/resources/application.yml`，根据部署模式选择配置：

**单服务模式（默认）：**
```yaml
service:
  original:
    url: http://实际后端地址:端口       # 修改为你的后端地址
  comet:
    base-url: http://Comet平台地址:端口  # 修改为你的 Comet 地址
```

**多服务集群模式（推荐生产）：**
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

### 编译运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/mcp-adapter-0.0.1-SNAPSHOT.jar
```

### LLM 端配置（以 Claude 为例）

在 Claude 的 MCP 配置中添加：

```json
{
  "mcpServers": {
    "my-service": {
      "url": "http://localhost:8124/mcp"
    }
  }
}
```


---

## 配置说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `spring.ai.mcp.server.transport` | MCP 传输协议 | SSE |
| `spring.ai.mcp.servlet.path` | MCP 端点路径 | /mcp |
| `server.port` | 适配器端口 | 8124 |
| `service.original.url` | 后端服务 URL | http://127.0.0.1:9020 |
| `service.original.connect-timeout` | 连接超时(ms) | 5000 |
| `service.original.read-timeout` | 读取超时(ms) | 30000 |
| `service.comet.base-url` | Comet 平台地址（单服务模式） | (按需) |
| `service.comet.enabled` | 是否启用 Comet | true |
| `service.comet.timeout` | Comet 请求超时(ms) | 5000 |
| `service.registry.services[].id` | 微服务标识（多服务模式） | — |
| `service.registry.services[].base-url` | 微服务基础 URL | — |
| `service.registry.services[].route-patterns` | 路由匹配模式（如 /pf/**） | — |
| `service.registry.services[].comet.host` | 该服务 Comet 主机 | 127.0.0.1 |
| `service.registry.services[].comet.port` | 该服务 Comet 端口 | 9020 |
| `service.message-format.enabled` | 是否启用标准报文 | true |
| `service.message-format.template-dir` | 报文模板目录 | ./templates |

---

## 项目结构

```
mcp-adapter/
├── pom.xml                           # Maven 依赖管理
├── README.md                         # 项目说明文档
├── docs/                             # 设计文档
│   └── mcp-adapter-重构-多服务集群支持.md
├── templates/                        # 报文模板目录
│   └── _default.json                 # 默认模板（兜底）
└── src/main/java/com/mcp/
    ├── McpAdapterApplication.java    # 启动类
    ├── config/                       # 配置层
    │   ├── AppConfig.java            # 后端服务连接（单服务兜底）
    │   ├── CometConfig.java          # Comet 平台连接配置
    │   ├── MessageFormatConfig.java  # 标准报文格式配置
    │   ├── MessageTemplateLoader.java # 报文模板加载器
    │   ├── ServiceInstance.java      # ★ 微服务实例模型
    │   ├── ServiceRegistry.java      # ★ 微服务注册表
    │   └── ServiceRouter.java        # ★ 服务路由器（路径匹配）
    ├── scanner/                      # Schema 层
    │   ├── CometApiSchemaLoader.java # Comet Schema 加载器（多服务并行异步）
    │   ├── ApiParamSchema.java       # 接口参数 Schema 定义（含 serviceId）
    │   ├── SchemaLoadCompleteEvent.java # ★ 加载完成事件（触发动态工具注册）
    │   └── FieldDef.java             # 字段定义
    └── tool/                         # Tool 层
        ├── DynamicToolRegistrar.java # MCP Tool 注册器（双 Tool + 按需加载）
        ├── SchemaLoadCompleteListener.java # ★ 事件监听器（动态注册到 McpSyncServer）
        └── HttpForwarder.java        # HTTP 转发器（动态路由 + 止语引导）
```

---

## 设计要点总结

| 要点 | 决策 | 理由 |
|------|------|------|
| **Tool 数量** | 双 Tool：搜索 + 调用 | 避免 LLM 在多 Tool 间选择困难；搜索工具帮 LLM 快速定位正确 apiCode |
| **参数来源** | Comet 平台动态加载 | 接口变更无需改代码，实时同步 |
| **启动 & 注册** | 异步加载 + 事件驱动动态注册 | 多服务 Schema 后台并行加载，完成后发布事件，`SchemaLoadCompleteListener` 通过 `McpSyncServer.addTool()` 动态注册工具并通知客户端刷新，启动不阻塞 |
| **接口描述** | remark + 入参摘要 + URL 路径分组 | LLM 获得充分信息以区分接口，减少猜错概率 |
| **报文格式** | 可配置模板 + 运行时占位符解析 | 灵活适配金融标准报文结构 |
| **认证方式** | RestTemplate 拦截器 | 透明支持 basic/bearer 认证 |
| **错误处理** | 中文友好提示 | LLM 能理解错误并自行修正 |
| **响应格式** | JSON 美化 + 止语引导 | LLM 更易解析结构化数据；防止盲目遍历调用 |
