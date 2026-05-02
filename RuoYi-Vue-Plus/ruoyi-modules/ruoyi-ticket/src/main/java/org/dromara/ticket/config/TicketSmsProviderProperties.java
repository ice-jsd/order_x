package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.sms-provider")
public class TicketSmsProviderProperties {

    /**
     * 是否启用第三方短信取号能力。
     */
    private boolean enabled = false;

    /**
     * 短信平台接口地址，例如 http://sms-provider-host。
     */
    private String baseUrl;

    /**
     * API Key。
     */
    private String apiKey;

    /**
     * API Secret。
     */
    private String apiSecret;

    /**
     * 首版固定使用的设备编号。
     */
    private String equipno;

    /**
     * 第三方平台业务 ID。
     */
    private String appid;

    /**
     * 连接超时时间。
     */
    private int connectTimeoutMs = 10000;

    /**
     * 读取超时时间。
     */
    private int readTimeoutMs = 30000;

    /**
     * 短信平台未返回明确秒数时的本地租约兜底秒数。
     */
    private int leaseFallbackSeconds = 20 * 60;
}
