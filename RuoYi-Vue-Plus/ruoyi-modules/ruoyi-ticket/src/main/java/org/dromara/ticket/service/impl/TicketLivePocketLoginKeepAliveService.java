package org.dromara.ticket.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.config.TicketPythonExecutorProperties;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhonePlatformRelation;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketPhonePlatformRelationMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.service.TicketPythonExecutorClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketLivePocketLoginKeepAliveService {

    private static final String LIVEPOCKET_PLATFORM_CODE = "livepocket";
    private static final String LOGIN_STATUS_LOGGED_IN = "logged_in";
    private static final String LOGIN_STATUS_OFFLINE = "offline";
    private static final String DEFAULT_EXPIRED_MESSAGE = "登录上下文已失效";

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketPhonePlatformRelationMapper relationMapper;
    private final TicketPythonExecutorClient pythonExecutorClient;
    private final TicketPythonExecutorProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
        initialDelayString = "${ticket.python-executor.login-keep-alive-initial-delay-ms:60000}",
        fixedDelayString = "${ticket.python-executor.login-keep-alive-interval-ms:600000}"
    )
    public void runScheduledKeepAlive() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runKeepAliveOnce();
        } catch (Exception ex) {
            log.warn("livepocket login keepalive schedule failed", ex);
        } finally {
            running.set(false);
        }
    }

    public int runKeepAliveOnce() {
        if (!properties.isEnabled() || !properties.isLoginKeepAliveEnabled() || StringUtils.isBlank(properties.getBaseUrl())) {
            return 0;
        }
        TicketPlatformConfig platform = findLivePocketPlatform();
        if (platform == null || platform.getPlatformId() == null) {
            return 0;
        }
        List<TicketManagedAccount> accounts = loadKeepAliveAccounts(platform.getPlatformId());
        int processed = 0;
        for (TicketManagedAccount account : accounts) {
            keepAliveAccount(account);
            processed++;
        }
        return processed;
    }

    private TicketPlatformConfig findLivePocketPlatform() {
        return platformMapper.selectOne(Wrappers.lambdaQuery(TicketPlatformConfig.class)
            .and(wrapper -> wrapper
                .eq(TicketPlatformConfig::getPlatformCode, LIVEPOCKET_PLATFORM_CODE)
                .or()
                .eq(TicketPlatformConfig::getAdapterType, LIVEPOCKET_PLATFORM_CODE)
            )
            .orderByAsc(TicketPlatformConfig::getPlatformId)
            .last("limit 1"));
    }

    private List<TicketManagedAccount> loadKeepAliveAccounts(Long platformId) {
        int batchSize = Math.max(1, Math.min(properties.getLoginKeepAliveBatchSize(), 200));
        return accountMapper.selectList(Wrappers.lambdaQuery(TicketManagedAccount.class)
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getLoginStatus, LOGIN_STATUS_LOGGED_IN)
            .isNotNull(TicketManagedAccount::getLoginReqData)
            .ne(TicketManagedAccount::getLoginReqData, "")
            .orderByAsc(TicketManagedAccount::getLastLoginTime)
            .last("limit " + batchSize));
    }

    private void keepAliveAccount(TicketManagedAccount account) {
        try {
            Map<String, Object> result = pythonExecutorClient.keepAliveLivePocketLogin(account.getEmail(), account.getLoginReqData());
            if (Boolean.TRUE.equals(result.get("loggedIn"))) {
                markLoggedIn(account, StringUtils.defaultIfBlank(toStringValue(result.get("loginReqData")), account.getLoginReqData()));
            } else {
                markOffline(account, StringUtils.defaultIfBlank(toStringValue(result.get("message")), DEFAULT_EXPIRED_MESSAGE));
            }
        } catch (ServiceException ex) {
            log.warn("livepocket login keepalive skipped, accountId={}, email={}, message={}", account.getAccountId(), account.getEmail(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("livepocket login keepalive unexpected error, accountId={}, email={}", account.getAccountId(), account.getEmail(), ex);
        }
    }

    private void markLoggedIn(TicketManagedAccount account, String loginReqData) {
        Date now = new Date();
        TicketManagedAccount update = new TicketManagedAccount();
        update.setAccountId(account.getAccountId());
        update.setLoginReqData(loginReqData);
        update.setLoginStatus(LOGIN_STATUS_LOGGED_IN);
        update.setLastLoginTime(now);
        update.setLastError("");
        accountMapper.updateById(update);
        updateRelation(account, LOGIN_STATUS_LOGGED_IN, "", now);
    }

    private void markOffline(TicketManagedAccount account, String message) {
        Date now = new Date();
        TicketManagedAccount update = new TicketManagedAccount();
        update.setAccountId(account.getAccountId());
        update.setLoginReqData("");
        update.setLoginStatus(LOGIN_STATUS_OFFLINE);
        update.setLastError(message);
        accountMapper.updateById(update);
        updateRelation(account, LOGIN_STATUS_OFFLINE, message, now);
    }

    private void updateRelation(TicketManagedAccount account, String status, String message, Date now) {
        if (account.getAccountId() == null || account.getPlatformId() == null) {
            return;
        }
        relationMapper.update(null, Wrappers.lambdaUpdate(TicketPhonePlatformRelation.class)
            .set(TicketPhonePlatformRelation::getStatus, status)
            .set(TicketPhonePlatformRelation::getLastError, message)
            .set(TicketPhonePlatformRelation::getLastOperateTime, now)
            .eq(TicketPhonePlatformRelation::getPlatformId, account.getPlatformId())
            .eq(TicketPhonePlatformRelation::getAccountId, account.getAccountId()));
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
