package com.mcp.config;



import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 应用配置 — 绑定 application.yml 中 service.original.* 的配置项。
 */
@Configuration
@ConfigurationProperties(prefix = "service.original")
public class AppConfig {

    /** 原 Spring Boot 服务的基础 URL */
    private String url = "http://localhost:8080";

    /** 认证配置 */
    private Auth auth = new Auth();

    /** 连接超时（毫秒），默认 5 秒 */
    private int connectTimeout = 5000;

    /** 读取超时（毫秒），默认 30 秒 */
    private int readTimeout = 30000;

    public String originalUrl() { return url; }
    public Auth auth() { return auth; }
    public int getConnectTimeout() { return connectTimeout; }
    public int getReadTimeout() { return readTimeout; }

    public void setUrl(String url) { this.url = url; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
    public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }

    public static class Auth {
        private boolean enabled = false;
        private String type = "bearer";  // basic | bearer
        private String username;
        private String password;
        private String token;

        public boolean isEnabled() { return enabled; }
        public String getType() { return type; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getToken() { return token; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setType(String type) { this.type = type; }
        public void setUsername(String username) { this.username = username; }
        public void setPassword(String password) { this.password = password; }
        public void setToken(String token) { this.token = token; }
    }

    @Bean
    public RestTemplate restTemplate() {
        // 连接池：总上限 100，单路由上限 20，避免高并发时端口耗尽
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100);
        cm.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
            .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
            .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
            .setConnectionManager(cm)
            .setDefaultRequestConfig(requestConfig)
            .build();

        HttpComponentsClientHttpRequestFactory factory =
            new HttpComponentsClientHttpRequestFactory(httpClient);
        RestTemplate rt = new RestTemplate(factory);

        if (auth.isEnabled()) {
            rt.getInterceptors().add((request, body, execution) -> {
                if ("bearer".equalsIgnoreCase(auth.getType()) && auth.getToken() != null) {
                    request.getHeaders().setBearerAuth(auth.getToken());
                } else if ("basic".equalsIgnoreCase(auth.getType())) {
                    request.getHeaders().setBasicAuth(auth.getUsername(), auth.getPassword());
                }
                return execution.execute(request, body);
            });
        }
        return rt;
    }
}
