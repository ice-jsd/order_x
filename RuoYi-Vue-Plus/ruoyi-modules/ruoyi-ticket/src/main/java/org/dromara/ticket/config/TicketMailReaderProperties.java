package org.dromara.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.mail-reader")
public class TicketMailReaderProperties {

    /**
     * 是否启用自建邮箱读取。
     */
    private boolean enabled = true;

    /**
     * IMAPS 主机。
     */
    private String host = "mail.gjcytech.com";

    /**
     * IMAPS 端口。
     */
    private int port = 993;

    /**
     * 邮箱登录用户名。
     */
    private String username;

    /**
     * 邮箱登录密码。
     */
    private String password;

    /**
     * 读取的邮箱文件夹。
     *
     */
    private String folder = "INBOX";

    /**
     * 读取的邮箱文件夹列表。默认同时扫描收件箱、垃圾箱和垃圾邮件箱。
     */
    private List<String> folders = new ArrayList<>(List.of(
        "INBOX", "Trash", "Junk", "Spam", "Junk Mail", "Deleted Items"));

    /**
     * 是否自动扫描 IMAP 返回的全部文件夹。用于覆盖 Stalwart 把邮件投递到特殊垃圾邮件文件夹的情况。
     */
    private boolean scanAllFolders = true;

    /**
     * 从最新邮件开始最多扫描多少封。
     */
    private int maxScanCount = 50;

    /**
     * 连接超时时间。
     */
    private int connectTimeoutMs = 30000;

    /**
     * 读取超时时间。
     */
    private int readTimeoutMs = 60000;

    /**
     * 是否信任 SSL 证书。
     */
    private boolean sslTrustAll = true;

    /**
     * 是否自动同步邮箱账号池最新邮件。
     */
    private boolean autoSyncEnabled = true;

    /**
     * 邮箱账号池自动同步间隔。
     */
    private long syncFixedDelayMs = 120000L;

    /**
     * 每轮最多同步多少个邮箱账号。
     */
    private int syncBatchSize = 20;

    /**
     * 最新邮件正文摘要长度。
     */
    private int bodyExcerptLength = 1000;
}
