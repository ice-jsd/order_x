package org.dromara.ticket.service;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.ticket.config.TicketMailReaderProperties;
import org.springframework.stereotype.Service;

import java.io.Serial;
import java.io.Serializable;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketMailReaderService {

    private static final Pattern CONTEXT_CODE_PATTERN = Pattern.compile(
        "(?:验证码|校验码|动态码|code|verification|verify)[^0-9]{0,30}([0-9]{4,8})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANY_CODE_PATTERN = Pattern.compile("(?<!\\d)([0-9]{4,8})(?!\\d)");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIVED_FOR_PATTERN = Pattern.compile("for\\s+<?([^\\s<>;]+@[^\\s<>;]+)>?", Pattern.CASE_INSENSITIVE);

    private final TicketMailReaderProperties properties;

    public MailReadResult readLatestForEmail(String email) {
        if (!properties.isEnabled()) {
            throw new ServiceException("邮箱读取功能未启用");
        }
        if (StringUtils.isBlank(properties.getHost()) || StringUtils.isBlank(properties.getUsername())
            || StringUtils.isBlank(properties.getPassword())) {
            throw new ServiceException("邮箱读取配置不完整");
        }

        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(buildMailProperties());
            store = session.getStore("imaps");
            store.connect(properties.getHost(), properties.getUsername(), properties.getPassword());
            inbox = store.getFolder(StringUtils.blankToDefault(properties.getFolder(), "INBOX"));
            inbox.open(Folder.READ_ONLY);

            int count = inbox.getMessageCount();
            int start = Math.max(1, count - Math.max(1, properties.getMaxScanCount()) + 1);
            for (int index = count; index >= start; index--) {
                Message message = inbox.getMessage(index);
                if (!matchesRecipient(message, email)) {
                    continue;
                }
                return parseMatchedMessage(message);
            }
            throw new ServiceException("没有找到收件人为 " + email + " 的最新邮件");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("read latest mail failed for email={}", email, e);
            throw new ServiceException("读取邮箱失败: " + e.getMessage());
        } finally {
            closeQuietly(inbox);
            closeQuietly(store);
        }
    }

    public MailReadResult readLatestForMailbox(String username, String password) {
        if (!properties.isEnabled()) {
            throw new ServiceException("邮箱读取功能未启用");
        }
        if (StringUtils.isBlank(properties.getHost()) || StringUtils.isBlank(username)
            || StringUtils.isBlank(password)) {
            throw new ServiceException("邮箱读取配置不完整");
        }

        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(buildMailProperties());
            store = session.getStore("imaps");
            store.connect(properties.getHost(), username, password);
            inbox = store.getFolder(StringUtils.blankToDefault(properties.getFolder(), "INBOX"));
            inbox.open(Folder.READ_ONLY);

            int count = inbox.getMessageCount();
            if (count <= 0) {
                throw new ServiceException("邮箱没有邮件");
            }
            return parseMatchedMessage(inbox.getMessage(count));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("read latest mail failed for mailbox={}", username, e);
            throw new ServiceException("读取邮箱失败: " + e.getMessage());
        } finally {
            closeQuietly(inbox);
            closeQuietly(store);
        }
    }

    private Properties buildMailProperties() {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", properties.getHost());
        props.put("mail.imaps.port", String.valueOf(properties.getPort()));
        props.put("mail.imaps.ssl.enable", "true");
        if (properties.isSslTrustAll()) {
            props.put("mail.imaps.ssl.trust", "*");
            props.put("mail.imaps.ssl.checkserveridentity", "false");
        }
        return props;
    }

    private MailReadResult parseMatchedMessage(Message message) throws Exception {
        String subject = decodeText(message.getSubject());
        String body = extractBody(message);
        String verifyCode = extractVerifyCode(body);
        String activationUrl = extractActivationUrl(body);

        MailReadResult result = new MailReadResult();
        result.setSubject(subject);
        result.setFromAddress(extractFromAddress(message));
        result.setReceivedAt(message.getReceivedDate());
        result.setMessageId(firstHeader(message, "Message-ID"));
        result.setBodyExcerpt(buildBodyExcerpt(body));
        result.setVerifyCode(verifyCode);
        result.setActivationUrl(activationUrl);
        if (StringUtils.isBlank(verifyCode) && StringUtils.isBlank(activationUrl)) {
            result.setParsed(false);
            result.setMessage("未解析到验证码或激活链接");
        } else {
            result.setParsed(true);
            result.setMessage("解析成功");
        }
        return result;
    }

    private String extractFromAddress(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        Address first = from[0];
        if (first instanceof InternetAddress internetAddress) {
            return decodeText(internetAddress.toUnicodeString());
        }
        return decodeText(first.toString());
    }

    private boolean matchesRecipient(Message message, String email) throws MessagingException {
        String target = normalize(email);
        if (StringUtils.isBlank(target)) {
            return false;
        }

        List<String> candidates = new ArrayList<>();
        collectAddresses(candidates, message.getRecipients(Message.RecipientType.TO));
        collectAddresses(candidates, message.getRecipients(Message.RecipientType.CC));
        collectHeaders(candidates, message, "Delivered-To");
        collectHeaders(candidates, message, "X-Original-To");
        collectHeaders(candidates, message, "Envelope-To");
        collectHeaders(candidates, message, "To");
        collectHeaders(candidates, message, "Cc");
        collectReceivedFor(candidates, message);

        return candidates.stream().map(this::normalize).anyMatch(value -> value.contains(target));
    }

    private void collectAddresses(List<String> candidates, Address[] addresses) {
        if (addresses == null) {
            return;
        }
        for (Address address : addresses) {
            if (address instanceof InternetAddress internetAddress) {
                candidates.add(internetAddress.getAddress());
            } else if (address != null) {
                candidates.add(address.toString());
            }
        }
    }

    private void collectHeaders(List<String> candidates, Message message, String headerName) throws MessagingException {
        String[] values = message.getHeader(headerName);
        if (values == null) {
            return;
        }
        candidates.addAll(List.of(values));
    }

    private void collectReceivedFor(List<String> candidates, Message message) throws MessagingException {
        String[] values = message.getHeader("Received");
        if (values == null) {
            return;
        }
        for (String value : values) {
            Matcher matcher = RECEIVED_FOR_PATTERN.matcher(value);
            while (matcher.find()) {
                candidates.add(matcher.group(1));
            }
        }
    }

    private String extractBody(Object content) throws Exception {
        if (content == null) {
            return "";
        }
        if (content instanceof Message message) {
            return extractBody(message.getContent());
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain") || part.isMimeType("text/html") || part.getContent() instanceof Multipart) {
                    builder.append('\n').append(extractBody(part.getContent()));
                }
            }
            return builder.toString();
        }
        return content.toString();
    }

    private String extractVerifyCode(String body) {
        String text = stripHtml(body);
        Matcher contextMatcher = CONTEXT_CODE_PATTERN.matcher(text);
        if (contextMatcher.find()) {
            return contextMatcher.group(1);
        }
        Matcher anyMatcher = ANY_CODE_PATTERN.matcher(text);
        return anyMatcher.find() ? anyMatcher.group(1) : null;
    }

    private String extractActivationUrl(String body) {
        Matcher matcher = URL_PATTERN.matcher(body == null ? "" : body);
        String fallback = null;
        while (matcher.find()) {
            String url = cleanupUrl(matcher.group());
            if (StringUtils.isBlank(fallback)) {
                fallback = url;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("activate") || lower.contains("activation") || lower.contains("verify")
                || lower.contains("confirm") || lower.contains("token")) {
                return url;
            }
        }
        return fallback;
    }

    private String stripHtml(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("(?is)<script.*?</script>", " ")
            .replaceAll("(?is)<style.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&");
    }

    private String buildBodyExcerpt(String body) {
        String text = stripHtml(body)
            .replaceAll("\\s+", " ")
            .trim();
        int maxLength = Math.max(100, properties.getBodyExcerptLength());
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private String cleanupUrl(String value) {
        String cleaned = value.replace("&amp;", "&")
            .replaceAll("[\\]\\),.;，。；）】]+$", "");
        try {
            return URLDecoder.decode(cleaned, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return cleaned;
        }
    }

    private String firstHeader(Message message, String headerName) throws MessagingException {
        String[] values = message.getHeader(headerName);
        return values == null || values.length == 0 ? null : values[0];
    }

    private String decodeText(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    private void closeQuietly(Folder folder) {
        if (folder == null) {
            return;
        }
        try {
            if (folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(Store store) {
        if (store == null) {
            return;
        }
        try {
            if (store.isConnected()) {
                store.close();
            }
        } catch (Exception ignored) {
        }
    }

    @Data
    public static class MailReadResult implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private boolean parsed;
        private String verifyCode;
        private String activationUrl;
        private String subject;
        private String fromAddress;
        private Date receivedAt;
        private String messageId;
        private String bodyExcerpt;
        private String message;
    }
}
