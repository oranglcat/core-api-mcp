package com.mcp.tool;

import com.mcp.scanner.CometApiSchemaLoader;
import com.mcp.scanner.SchemaLoadCompleteEvent;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Schema 加载完成事件监听器。
 * <p>
 * 监听 {@link SchemaLoadCompleteEvent}，在异步多服务 Schema 加载完成后，
 * 通过 {@link McpSyncServer#addTool} 动态注册 MCP 工具到运行中的 MCP Server，
 * 并通知客户端刷新工具列表。
 * <p>
 * 此组件从 {@link DynamicToolRegistrar} 中拆分出来，目的是打破循环依赖：
 * <pre>
 *   DynamicToolRegistrar → (原) McpSyncServer → syncTools → DynamicToolRegistrar
 * </pre>
 * 现在 {@link DynamicToolRegistrar} 不再依赖 McpSyncServer，而此监听器独立处理动态注册。
 */
@Component
public class SchemaLoadCompleteListener implements ApplicationListener<SchemaLoadCompleteEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchemaLoadCompleteListener.class);

    private final McpSyncServer mcpSyncServer;
    private final DynamicToolRegistrar dynamicToolRegistrar;
    private final CometApiSchemaLoader cometLoader;

    /** 防止同一事件多次触发重复注册 */
    private volatile boolean toolsRegistered = false;

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
        // 防重复
        if (toolsRegistered) {
            log.debug("工具已动态注册，忽略重复事件");
            return;
        }

        if (mcpSyncServer == null) {
            log.warn("McpSyncServer 不可用（非标准 MCP 传输？），无法动态注册工具");
            return;
        }

        if (!cometLoader.isLoaded()) {
            log.warn("收到 Schema 完成事件但加载状态异常，跳过动态工具注册");
            return;
        }

        // 通过 DynamicToolRegistrar 获取已构建的 ToolCallback
        ToolCallback[] callbacks = dynamicToolRegistrar.getToolCallbacks();
        if (callbacks.length == 0) {
            log.warn("getToolCallbacks() 返回空，跳过动态注册。服务加载摘要: {}",
                cometLoader.getLoadSummary());
            return;
        }

        try {
            // 转换为 McpServer SyncToolSpecification 并注册
            List<McpServerFeatures.SyncToolSpecification> specs =
                McpToolUtils.toSyncToolSpecifications(callbacks);

            for (McpServerFeatures.SyncToolSpecification spec : specs) {
                mcpSyncServer.addTool(spec);
            }

            // 通知所有已连接客户端：工具列表已变更
            mcpSyncServer.notifyToolsListChanged();

            log.info("✅ 动态注册 MCP 工具完成: {} 个工具 ({}), 已通知客户端刷新",
                callbacks.length, cometLoader.getLoadSummary());

            toolsRegistered = true;

        } catch (Exception e) {
            log.error("动态注册 MCP 工具失败: {}", e.getMessage(), e);
        }
    }
}
