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
    private static final String PARSE_TYPE_ACTIVATION_URL = "activation_url";
    private static final String PARSE_TYPE_VERIFY_CODE = "verify_code";
    private static final String PARSE_TYPE_UNKNOWN = "unknown";

    private final TicketMailReaderProperties properties;

    public MailReadResult readLatestForMailbox(String username, String password) {
        return readLatestForMailbox(username, password, null, "邮箱没有邮件");
    }

    public MailReadResult readLatestVerifyCodeForMailbox(String username, String password) {
        return readLatestForMailbox(username, password, PARSE_TYPE_VERIFY_CODE, "邮箱没有验证码邮件");
    }

    public MailReadResult readLatestActivationUrlForMailbox(String username, String password) {
        return readLatestForMailbox(username, password, PARSE_TYPE_ACTIVATION_URL, "邮箱没有激活链接邮件");
    }

    private MailReadResult readLatestForMailbox(String username, String password, String expectedParseType, String notFoundMessage) {
        if (!properties.isEnabled()) {
            throw new ServiceException("邮箱读取功能未启用");
        }
        if (StringUtils.isBlank(properties.getHost()) || StringUtils.isBlank(username)
            || StringUtils.isBlank(password)) {
            throw new ServiceException("邮箱读取配置不完整");
        }

        Store store = null;
        try {
            Session session = Session.getInstance(buildMailProperties());
            store = session.getStore("imaps");
            store.connect(properties.getHost(), username, password);

            MailReadResult latestResult = null;
            for (String folderName : resolveFolderNames(store)) {
                MailReadResult folderResult = readLatestFromFolder(store, folderName, expectedParseType);
                latestResult = pickLatest(latestResult, folderResult);
            }
            if (latestResult != null) {
                return latestResult;
            }
            throw new ServiceException(notFoundMessage);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("read latest mail failed for mailbox={}", username, e);
            throw new ServiceException("读取邮箱失败: " + e.getMessage());
        } finally {
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

    private List<String> resolveFolderNames(Store store) {
        List<String> configured = properties.getFolders();
        if (configured == null || configured.isEmpty()) {
            configured = List.of(StringUtils.blankToDefault(properties.getFolder(), "INBOX"));
        }
        List<String> folderNames = new ArrayList<>(configured.stream()
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList());

        if (properties.isScanAllFolders()) {
            collectStoreFolderNames(store, folderNames);
        }

        return folderNames.stream().filter(StringUtils::isNotBlank).distinct().toList();
    }

    private void collectStoreFolderNames(Store store, List<String> folderNames) {
        try {
            for (Folder folder : store.getDefaultFolder().list("*")) {
                collectFolderNameRecursive(folder, folderNames);
            }
        } catch (Exception e) {
            log.warn("list mail folders failed, fallback to configured folders", e);
        }
    }

    private void collectFolderNameRecursive(Folder folder, List<String> folderNames) throws MessagingException {
        if (folder == null) {
            return;
        }
        folderNames.add(folder.getFullName());
        if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0) {
            for (Folder child : folder.list()) {
                collectFolderNameRecursive(child, folderNames);
            }
        }
    }

    private MailReadResult readLatestFromFolder(Store store, String folderName, String expectedParseType) throws Exception {
        Folder folder = null;
        try {
            folder = store.getFolder(folderName);
            if (folder == null || !folder.exists()) {
                log.debug("mail folder does not exist, folder={}", folderName);
                return null;
            }
            folder.open(Folder.READ_ONLY);

            int count = folder.getMessageCount();
            if (count <= 0) {
                return null;
            }
            int start = Math.max(1, count - Math.max(1, properties.getMaxScanCount()) + 1);
            for (int index = count; index >= start; index--) {
                MailReadResult result = parseMatchedMessage(folder.getMessage(index), folderName);
                if (StringUtils.isBlank(expectedParseType) || expectedParseType.equals(result.getParseType())) {
                    return result;
                }
            }
            return null;
        } catch (MessagingException e) {
            log.warn("read mail folder failed, folder={}", folderName, e);
            return null;
        } finally {
            closeQuietly(folder);
        }
    }

    private MailReadResult pickLatest(MailReadResult current, MailReadResult candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        Date currentDate = current.getReceivedAt();
        Date candidateDate = candidate.getReceivedAt();
        if (candidateDate == null) {
            return current;
        }
        if (currentDate == null || candidateDate.after(currentDate)) {
            return candidate;
        }
        return current;
    }

    private MailReadResult parseMatchedMessage(Message message) throws Exception {
        return parseMatchedMessage(message, null);
    }

    private MailReadResult parseMatchedMessage(Message message, String folderName) throws Exception {
        String subject = decodeText(message.getSubject());
        String body = extractBody(message);
        String activationUrl = extractActivationUrl(body);
        String verifyCode = StringUtils.isBlank(activationUrl) ? extractVerifyCode(body) : null;

        MailReadResult result = new MailReadResult();
        result.setSubject(subject);
        result.setFromAddress(extractFromAddress(message));
        result.setReceivedAt(message.getReceivedDate());
        result.setMessageId(firstHeader(message, "Message-ID"));
        result.setFolderName(folderName);
        result.setBodyExcerpt(buildBodyExcerpt(body));

        if (StringUtils.isNotBlank(activationUrl)) {
            result.setParsed(true);
            result.setParseType(PARSE_TYPE_ACTIVATION_URL);
            result.setActivationUrl(activationUrl);
            result.setVerifyCode(null);
            result.setMessage("解析到激活链接");
        } else if (StringUtils.isNotBlank(verifyCode)) {
            result.setParsed(true);
            result.setParseType(PARSE_TYPE_VERIFY_CODE);
            result.setVerifyCode(verifyCode);
            result.setActivationUrl(null);
            result.setMessage("解析到验证码");
        } else {
            result.setParsed(false);
            result.setParseType(PARSE_TYPE_UNKNOWN);
            result.setVerifyCode(null);
            result.setActivationUrl(null);
            result.setMessage("未解析到验证码或激活链接");
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
        while (matcher.find()) {
            String url = cleanupUrl(matcher.group());
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("activate") || lower.contains("activation") || lower.contains("verify")
                || lower.contains("confirm") || lower.contains("token")) {
                return url;
            }
        }
        return null;
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
        private String parseType;
        private String verifyCode;
        private String activationUrl;
        private String subject;
        private String fromAddress;
        private Date receivedAt;
        private String messageId;
        private String folderName;
        private String bodyExcerpt;
        private String message;
    }
}
