package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.stalwart")
public class TicketStalwartProperties {

    /**
     * 是否启用 Stalwart 管理 API。
     */
    private boolean enabled = true;

    /**
     * Stalwart 管理接口地址，例如 https://mail.orderx.top:9443。
     */
    private String baseUrl;

    /**
     * Stalwart 管理 API Key。
     */
    private String apiKey;

    /**
     * 自动生成邮箱使用的域名。
     */
    private String domain = "orderx.top";

    /**
     * 连接超时时间。
     */
    private int connectTimeoutMs = 10000;

    /**
     * 读取超时时间。
     */
    private int readTimeoutMs = 30000;

    /**
     * 是否信任自签证书。
     */
    private boolean sslTrustAll = true;

    /**
     * 创建 N 个邮箱时最多尝试 N * factor 次。
     */
    private int maxCreateAttemptFactor = 5;
}
