package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.hands-form-extension")
public class TicketHandsFormExtensionProperties {

    /**
     * 是否启用 Hands Form Chrome 扩展执行链路。
     */
    private boolean enabled = true;

    /**
     * 扩展调用后端 API 的共享密钥。
     */
    private String apiSecret = "change-me-hands-extension-secret";
}
