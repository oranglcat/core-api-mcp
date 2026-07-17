package com.mcp.tool;

import com.mcp.scanner.CometApiSchemaLoader;
import com.mcp.scanner.SchemaLoadCompleteEvent;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Schema 加载完成事件监听器。
 * <p>
 * 监听 {@link SchemaLoadCompleteEvent}，在异步多服务 Schema 加载完成后，
 * 刷新已注册工具的内部查找表（codeToSchemaMap），并通知 MCP 客户端工具列表已变更。
 * <p>
 * 注意：工具本身已在 {@link DynamicToolRegistrar#getToolCallbacks()} 中注册，
 * 此处不再重复 addTool()。工具内部引用 {@code codeToSchemaMap} 类字段，
 * 调用 refreshCodeToSchemaMap() 后数据自动可见。
 */
@Component
public class SchemaLoadCompleteListener implements ApplicationListener<SchemaLoadCompleteEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchemaLoadCompleteListener.class);

    private final McpSyncServer mcpSyncServer;
    private final DynamicToolRegistrar dynamicToolRegistrar;
    private final CometApiSchemaLoader cometLoader;

    public SchemaLoadCompleteListener(
            @Autowired(required = false) McpSyncServer mcpSyncServer,
            DynamicToolRegistrar dynamicToolRegistrar,
            CometApiSchemaLoader cometLoader) {
        this.mcpSyncServer = mcpSyncServer;
        this.dynamicToolRegistrar = dynamicToolRegistrar;
        this.cometLoader = cometLoader;
    }

    @Override
    public void onApplicationEvent(SchemaLoadCompleteEvent event) {
        if (mcpSyncServer == null) {
            log.warn("McpSyncServer 不可用（非标准 MCP 传输？），跳过动态刷新");
            return;
        }

        if (!cometLoader.isLoaded()) {
            log.warn("收到 Schema 完成事件但加载状态异常，跳过刷新");
            return;
        }

        // 刷新已注册 ToolCallback 中的 codeToSchemaMap（工具在 getToolCallbacks() 中已注册）
        dynamicToolRegistrar.refreshCodeToSchemaMap();

        // 通知已连接客户端：工具列表已变更（工具名未变，但搜索范围已完整）
        try {
            mcpSyncServer.notifyToolsListChanged();
            log.info("Schema 加载完成，已刷新 codeToSchemaMap 并通知客户端: {}",
                cometLoader.getLoadSummary());
        } catch (Exception e) {
            log.error("通知客户端工具列表变更失败: {}", e.getMessage(), e);
        }
    }
}
