package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.python-executor")
public class TicketPythonExecutorProperties {

    /**
     * 是否启用 Python 执行器。
     */
    private boolean enabled = true;

    /**
     * Hands Form 执行模式。python 使用服务器 Playwright，extension 回退到旧 Chrome 扩展。
     */
    private String handsFormExecutionMode = "python";

    /**
     * Python HTTP 服务地址，用于页面解析、资料更新、批量注册/登录等请求。
     */
    private String baseUrl;

    /**
     * HTTP 请求超时时间(ms)。
     */
    private int timeoutMs = 60000;

    /**
     * LivePocket 资料更新请求超时时间(ms)。改姓可能包含 WAF、邮箱验证码等待和重试，耗时会明显长于页面解析。
     */
    private int profileTimeoutMs = 900000;

    /**
     * 是否启用 LivePocket 登录态 /my_top 定时保活。
     */
    private boolean loginKeepAliveEnabled = true;

    /**
     * LivePocket 登录态保活每轮最多处理账号数。
     */
    private int loginKeepAliveBatchSize = 20;

    /**
     * LivePocket 登录态保活初始延迟(ms)。
     */
    private int loginKeepAliveInitialDelayMs = 60000;

    /**
     * LivePocket 登录态保活间隔(ms)。
     */
    private int loginKeepAliveIntervalMs = 600000;

    /**
     * 提供给 Python 回调获取邮箱、短信验证码等能力的 Java 外部账号接口地址。
     */
    private String backendBaseUrl;

    /**
     * Python/LivePocket 队列专用 Redis，和系统默认 Redis 分离。
     */
    private Redis redis = new Redis();

    /**
     * Python 批量注册结果 Stream。
     */
    private String registerResultStreamKey = "ticket:livepocket:register:stream:result";

    /**
     * Java 消费批量注册结果的 last id 缓存 key。
     */
    private String registerResultLastIdKey = "ticket:livepocket:register:result:last-id";

    /**
     * Python 批量登录结果 Stream。
     */
    private String loginResultStreamKey = "ticket:livepocket:login:stream:result";

    /**
     * Java 消费批量登录结果的 last id 缓存 key。
     */
    private String loginResultLastIdKey = "ticket:livepocket:login:result:last-id";

    /**
     * 抽票到期时段延迟队列。
     */
    private String delayedZsetKey = "ticket:lottery:zset:delayed";

    /**
     * Python 抽票待消费 Stream。
     */
    private String readyQueueKey = "ticket:lottery:stream:ready";

    /**
     * Python 抽票结果 Stream。
     */
    private String resultStreamKey = "ticket:lottery:stream:result";

    /**
     * Java 消费抽票结果的 last id 缓存 key。
     */
    private String resultLastIdKey = "ticket:lottery:result:last-id";

    /**
     * 抽票账号级 job payload 缓存 key 前缀。
     */
    private String jobKeyPrefix = "ticket:lottery:job:";

    /**
     * 普通抢票延迟队列。
     */
    private String flashSaleDelayedZsetKey = "ticket:flash-sale:zset:delayed";

    /**
     * 普通抢票待消费 Stream。
     */
    private String flashSaleReadyQueueKey = "ticket:flash-sale:stream:ready";

    /**
     * 普通抢票 job payload 缓存 key 前缀。
     */
    private String flashSaleJobKeyPrefix = "ticket:flash-sale:job:";

    /**
     * 抽票账号级 job payload 保留时间。
     */
    private long jobTtlSeconds = 7 * 24 * 3600L;

    /**
     * 抽票账号锁 key 前缀，供清理/排查使用。
     */
    private String accountLockKeyPrefix = "ticket:lottery:lock:account:";

    /**
     * Python 活动解析待消费 Stream。
     */
    private String eventParseReadyStreamKey = "ticket:lottery:event-parse:stream:ready";

    /**
     * Python 活动解析结果 Stream。
     */
    private String eventParseResultStreamKey = "ticket:lottery:event-parse:stream:result";

    /**
     * 活动解析 job payload 缓存 key 前缀。
     */
    private String eventParseJobKeyPrefix = "ticket:lottery:event-parse:job:";

    /**
     * Java 消费活动解析结果的 last id 缓存 key。
     */
    private String eventParseResultLastIdKey = "ticket:lottery:event-parse:result:last-id";

    /**
     * 活动解析 job payload 保留时间。
     */
    private long eventParseJobTtlSeconds = 3600L;

    /**
     * 每轮最多从 delayed 推进到 ready 的时段数。
     */
    private int delayedPromoteBatchSize = 100;

    /**
     * 每轮最多消费的 Python result 事件数。
     */
    private int queueConsumeBatchSize = 20;

    /**
     * delayed 推进轮询间隔，供 @Scheduled 配置兜底使用。
     */
    private int delayedPollIntervalMs = 1000;

    /**
     * ready 队列消费轮询间隔，供 @Scheduled 配置兜底使用。
     */
    private int queuePollIntervalMs = 1000;

    @Data
    public static class Redis {

        /**
         * Redis 主机。
         */
        private String host = "127.0.0.1";

        /**
         * Redis 端口。
         */
        private int port = 6379;

        /**
         * Redis 密码。
         */
        private String password;

        /**
         * Redis DB。
         */
        private int database = 0;

        /**
         * 命令超时时间。
         */
        private Duration timeout = Duration.ofSeconds(10);

        /**
         * Redis 连接最小空闲数。
         */
        private int connectionMinimumIdleSize = 32;

        /**
         * Redis 连接池大小。普通抢票会一次性写入大量 job，需要高于默认值。
         */
        private int connectionPoolSize = 512;

        /**
         * Redis 订阅连接池大小。
         */
        private int subscriptionConnectionPoolSize = 32;

        /**
         * Redis 命令重试次数。
         */
        private int retryAttempts = 8;

        /**
         * Redis 命令重试间隔(ms)。
         */
        private int retryIntervalMs = 1000;

        /**
         * 是否启用 SSL。
         */
        private boolean ssl = false;
    }
}
