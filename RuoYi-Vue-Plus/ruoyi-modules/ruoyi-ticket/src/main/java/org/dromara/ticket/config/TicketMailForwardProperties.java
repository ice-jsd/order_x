package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.mail-forward")
public class TicketMailForwardProperties {

    /**
     * 是否启用邮件转发功能。
     */
    private boolean enabled = false;

    /**
     * SMTP 主机。
     */
    private String host;

    /**
     * SMTP 端口。
     */
    private Integer port = 465;

    /**
     * 是否开启账号认证。
     */
    private boolean auth = true;

    /**
     * 发件人。
     */
    private String from;

    /**
     * SMTP 用户名。
     */
    private String user;

    /**
     * SMTP 密码。
     */
    private String pass;

    /**
     * 是否开启 STARTTLS。
     */
    private boolean starttlsEnable = true;

    /**
     * 是否开启 SSL。
     */
    private boolean sslEnable = true;

    /**
     * 发送超时。
     */
    private Long timeout = 30000L;

    /**
     * 连接超时。
     */
    private Long connectionTimeout = 30000L;
}
