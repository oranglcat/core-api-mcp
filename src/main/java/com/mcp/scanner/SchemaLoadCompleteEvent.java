package com.mcp.scanner;

import org.springframework.context.ApplicationEvent;

/**
 * Schema 加载完成事件。
 * <p>
 * 当 {@link CometApiSchemaLoader} 中所有后端服务的 Schema 加载完毕（无论成功或失败）时发布。
 * {@link com.mcp.tool.DynamicToolRegistrar} 监听此事件，动态将工具注册到 MCP Server。
 */
public class SchemaLoadCompleteEvent extends ApplicationEvent {

    public SchemaLoadCompleteEvent(Object source) {
        super(source);
    }
}
