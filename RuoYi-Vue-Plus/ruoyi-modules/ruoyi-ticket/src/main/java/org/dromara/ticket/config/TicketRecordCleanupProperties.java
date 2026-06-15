package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.record-cleanup")
public class TicketRecordCleanupProperties {

    /**
     * 是否启用邮件记录和订单记录自动清理。
     */
    private boolean enabled = true;

    /**
     * 邮件记录保留天数。
     */
    private int mailRecordRetentionDays = 30;

    /**
     * 订单执行记录保留天数。
     */
    private int orderExecutionRetentionDays = 30;

    /**
     * 自动清理 cron。
     */
    private String cron = "0 30 3 * * ?";

    /**
     * 每批清理数量。
     */
    private int batchSize = 1000;
}
