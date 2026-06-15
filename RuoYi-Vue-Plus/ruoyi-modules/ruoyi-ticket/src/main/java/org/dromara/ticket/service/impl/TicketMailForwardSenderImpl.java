package org.dromara.ticket.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.mail.MailAccount;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ticket.config.TicketMailForwardProperties;
import org.dromara.ticket.service.TicketMailClient;
import org.dromara.ticket.service.TicketMailForwardSender;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketMailForwardSenderImpl implements TicketMailForwardSender {

    private final TicketMailForwardProperties properties;
    private final TicketMailClient ticketMailClient;

    @Override
    public String send(String targetEmail, String subject, String content) {
        if (!properties.isEnabled()) {
            throw new ServiceException("邮件转发功能未启用");
        }
        if (isIncomplete()) {
            throw new ServiceException("转发邮箱配置不完整");
        }
        try {
            return ticketMailClient.send(buildMailAccount(), List.of(targetEmail), subject, content);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("邮件转发发送失败: " + StrUtil.blankToDefault(ex.getMessage(), "unknown"));
        }
    }

    private boolean isIncomplete() {
        if (StrUtil.hasBlank(properties.getHost(), properties.getFrom()) || properties.getPort() == null || properties.getPort() <= 0) {
            return true;
        }
        return properties.isAuth() && StrUtil.hasBlank(properties.getUser(), properties.getPass());
    }

    private MailAccount buildMailAccount() {
        MailAccount account = new MailAccount();
        account.setHost(properties.getHost());
        account.setPort(properties.getPort());
        account.setAuth(properties.isAuth());
        account.setFrom(properties.getFrom());
        account.setUser(properties.getUser());
        account.setPass(properties.getPass());
        account.setSocketFactoryPort(properties.getPort());
        account.setStarttlsEnable(properties.isStarttlsEnable());
        account.setSslEnable(properties.isSslEnable());
        account.setTimeout(properties.getTimeout());
        account.setConnectionTimeout(properties.getConnectionTimeout());
        return account;
    }
}
