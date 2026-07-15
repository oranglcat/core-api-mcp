package com.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MCP Adapter 启动类。
 * <p>
 * 作为原 Spring Boot 服务的 MCP 适配层，将 REST API 自动注册为 MCP Tool，
 * 供 Claude 等 LLM 调用。原服务零修改。
 */
@SpringBootApplication
@EnableConfigurationProperties
public class McpAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpAdapterApplication.class, args);
    }
}
