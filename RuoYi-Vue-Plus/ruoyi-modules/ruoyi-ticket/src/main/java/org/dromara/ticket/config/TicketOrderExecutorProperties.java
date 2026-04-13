package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.order-executor")
public class TicketOrderExecutorProperties {

    /**
     * 执行器调度模式(redis/http)
     */
    private String mode = "redis";

    /**
     * Go 执行器服务地址
     */
    private String baseUrl = "http://127.0.0.1:8099";

    /**
     * 调度接口路径
     */
    private String dispatchPath = "/internal/purchase-task/dispatch";

    /**
     * 调度超时时间(ms)
     */
    private int timeoutMs = 5000;

    /**
     * Redis Stream 主队列
     */
    private String streamKey = "ticket:purchase:stream:ready";

    /**
     * 延迟调度 zset
     */
    private String delayedZsetKey = "ticket:purchase:zset:delayed";

    /**
     * 任务载荷 key 前缀
     */
    private String jobKeyPrefix = "ticket:purchase:job:";

    /**
     * 心跳 key 前缀
     */
    private String heartbeatKeyPrefix = "ticket:purchase:heartbeat:";

    /**
     * 账号锁 key 前缀
     */
    private String accountLockKeyPrefix = "ticket:purchase:lock:account:";

    /**
     * 任务载荷保留时间(秒)
     */
    private long jobTtlSeconds = 86400;

    /**
     * 执行心跳超时时间(秒)
     */
    private long heartbeatTimeoutSeconds = 30;
}
