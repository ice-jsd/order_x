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
import jakarta.mail.UIDFolder;
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
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketMailReaderService {

    private static final Pattern CONTEXT_CODE_PATTERN = Pattern.compile(
        "(?:验证码|校验码|动态码|認証コード|認証番号|認証|確認コード|code|verification|verify)[^0-9]{0,50}([0-9]{4,8})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANY_CODE_PATTERN = Pattern.compile("(?<!\\d)([0-9]{4,8})(?!\\d)");
    private static final Pattern YEAR_PATTERN = Pattern.compile("^(?:19|20)\\d{2}$");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LOTTERY_APPLICATION_NO_PATTERN = Pattern.compile(
        "(?:申込番号|受付番号|抽選受付番号)\\s*(?:[：:]|\\[|【|\\(|（)?\\s*([0-9]+)\\s*(?:\\]|】|\\)|）)?");
    private static final String PARSE_TYPE_ACTIVATION_URL = "activation_url";
    private static final String PARSE_TYPE_VERIFY_CODE = "verify_code";
    private static final String PARSE_TYPE_LOTTERY_APPLIED = "lottery_applied";
    private static final String PARSE_TYPE_LOTTERY_SELECTED = "lottery_selected";
    private static final String PARSE_TYPE_LOTTERY_REJECTED = "lottery_rejected";
    private static final String PARSE_TYPE_PURCHASE_COMPLETED = "purchase_completed";
    private static final String PARSE_TYPE_UNKNOWN = "unknown";
    private static final String LOTTERY_RESULT_APPLIED = "applied";
    private static final String LOTTERY_RESULT_SELECTED = "selected";
    private static final String LOTTERY_RESULT_REJECTED = "rejected";

    private final TicketMailReaderProperties properties;

    public MailReadResult readLatestForMailbox(String username, String password) {
        return readLatestForMailbox(username, password, null, "邮箱没有邮件");
    }

    public List<MailReadResult> readRecentForMailbox(String username, String password) {
        return readRecentForMailbox(username, password, null, "邮箱没有邮件", null);
    }

    public List<MailReadResult> readRecentForGlobalMailbox() {
        return readRecentForGlobalMailbox(folderName -> null).stream()
            .flatMap(batch -> batch.getResults().stream())
            .toList();
    }

    public List<GlobalMailReadBatch> readRecentForGlobalMailbox(Function<String, GlobalMailUidCursor> cursorLoader) {
        String username = StringUtils.defaultIfBlank(properties.getGlobalUsername(), properties.getUsername());
        String password = StringUtils.defaultIfBlank(properties.getGlobalPassword(), properties.getPassword());
        if (!properties.isEnabled()) {
            throw new ServiceException("邮箱读取功能未启用");
        }
        if (StringUtils.isBlank(properties.getHost()) || StringUtils.isBlank(username)
            || StringUtils.isBlank(password)) {
            throw new ServiceException("中心邮箱读取配置不完整");
        }

        Store store = null;
        try {
            Session session = Session.getInstance(buildMailProperties());
            store = session.getStore("imaps");
            store.connect(properties.getHost(), username, password);

            List<GlobalMailReadBatch> batches = new ArrayList<>();
            for (String folderName : resolveFolderNames(store, properties.getGlobalFolders(), properties.isGlobalScanAllFolders())) {
                GlobalMailUidCursor cursor = cursorLoader == null ? null : cursorLoader.apply(folderName);
                GlobalMailReadBatch batch = readRecentFromGlobalFolder(store, folderName, cursor);
                if (batch != null && !batch.getResults().isEmpty()) {
                    batches.add(batch);
                }
            }
            if (!batches.isEmpty()) {
                return batches;
            }
            throw new ServiceException("中心邮箱没有邮件");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("read latest mail failed for global mailbox={}", username, e);
            throw new ServiceException("读取中心邮箱失败: " + e.getMessage());
        } finally {
            closeQuietly(store);
        }
    }

    public MailReadResult readLatestVerifyCodeForMailbox(String username, String password) {
        return readLatestForMailbox(username, password, PARSE_TYPE_VERIFY_CODE, "邮箱没有验证码邮件");
    }

    public MailReadResult readLatestVerifyCodeForMailbox(String username, String password, Date minReceivedAt) {
        return readLatestForMailbox(username, password, PARSE_TYPE_VERIFY_CODE, "验证码未收到，请稍后重试", minReceivedAt);
    }

    public MailReadResult readLatestActivationUrlForMailbox(String username, String password) {
        return readLatestForMailbox(username, password, PARSE_TYPE_ACTIVATION_URL, "邮箱没有激活链接邮件");
    }

    public MailReadResult reparseStoredMailRecord(String subject, String bodyContent, String bodyExcerpt) {
        String rawBody = StringUtils.defaultIfBlank(bodyContent, bodyExcerpt);
        String normalizedExcerpt = StringUtils.defaultIfBlank(bodyExcerpt, buildBodyExcerpt(rawBody));
        return parseMailContent(subject, rawBody, normalizedExcerpt, null, null, null, null);
    }

    private MailReadResult readLatestForMailbox(String username, String password, String expectedParseType, String notFoundMessage) {
        return readLatestForMailbox(username, password, expectedParseType, notFoundMessage, null);
    }

    private MailReadResult readLatestForMailbox(String username, String password, String expectedParseType, String notFoundMessage, Date minReceivedAt) {
        List<MailReadResult> recent = readRecentForMailbox(username, password, expectedParseType, notFoundMessage, minReceivedAt);
        return recent.stream()
            .max(Comparator.comparing(TicketMailReaderService.MailReadResult::getReceivedAt,
                Comparator.nullsLast(Date::compareTo)))
            .orElseThrow(() -> new ServiceException(notFoundMessage));
    }

    private List<MailReadResult> readRecentForMailbox(String username, String password, String expectedParseType, String notFoundMessage, Date minReceivedAt) {
        return readRecentForMailbox(
            username,
            password,
            expectedParseType,
            notFoundMessage,
            minReceivedAt,
            properties.getFolders(),
            properties.isScanAllFolders(),
            properties.getMaxScanCount()
        );
    }

    private List<MailReadResult> readRecentForMailbox(
        String username,
        String password,
        String expectedParseType,
        String notFoundMessage,
        Date minReceivedAt,
        List<String> configuredFolders,
        boolean scanAllFolders,
        int maxScanCount
    ) {
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

            List<MailReadResult> results = new ArrayList<>();
            for (String folderName : resolveFolderNames(store, configuredFolders, scanAllFolders)) {
                results.addAll(readRecentFromFolder(store, folderName, expectedParseType, minReceivedAt, maxScanCount));
            }
            List<MailReadResult> deduplicated = deduplicateRecentResults(results);
            if (!deduplicated.isEmpty()) {
                return deduplicated;
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
        props.put("mail.imaps.connectiontimeout", String.valueOf(properties.getConnectTimeoutMs()));
        props.put("mail.imaps.timeout", String.valueOf(properties.getReadTimeoutMs()));
        props.put("mail.imaps.writetimeout", String.valueOf(properties.getReadTimeoutMs()));
        if (properties.isSslTrustAll()) {
            props.put("mail.imaps.ssl.trust", "*");
            props.put("mail.imaps.ssl.checkserveridentity", "false");
        }
        return props;
    }

    private List<String> resolveFolderNames(Store store) {
        return resolveFolderNames(store, properties.getFolders(), properties.isScanAllFolders());
    }

    private List<String> resolveFolderNames(Store store, List<String> configuredFolders, boolean scanAllFolders) {
        List<String> configured = configuredFolders;
        if (configured == null || configured.isEmpty()) {
            configured = List.of(StringUtils.blankToDefault(properties.getFolder(), "INBOX"));
        }
        List<String> folderNames = new ArrayList<>(configured.stream()
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList());

        if (scanAllFolders) {
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

    private MailReadResult readLatestFromFolder(Store store, String folderName, String expectedParseType, Date minReceivedAt) throws Exception {
        List<MailReadResult> results = readRecentFromFolder(store, folderName, expectedParseType, minReceivedAt);
        return results.stream()
            .max(Comparator.comparing(TicketMailReaderService.MailReadResult::getReceivedAt,
                Comparator.nullsLast(Date::compareTo)))
            .orElse(null);
    }

    private List<MailReadResult> readRecentFromFolder(Store store, String folderName, String expectedParseType, Date minReceivedAt) throws Exception {
        return readRecentFromFolder(store, folderName, expectedParseType, minReceivedAt, properties.getMaxScanCount());
    }

    private List<MailReadResult> readRecentFromFolder(Store store, String folderName, String expectedParseType, Date minReceivedAt, int maxScanCount) throws Exception {
        Folder folder = null;
        try {
            folder = store.getFolder(folderName);
            if (folder == null || !folder.exists()) {
                log.debug("mail folder does not exist, folder={}", folderName);
                return List.of();
            }
            folder.open(Folder.READ_ONLY);

            int count = folder.getMessageCount();
            if (count <= 0) {
                return List.of();
            }
            int start = Math.max(1, count - Math.max(1, maxScanCount) + 1);
            List<MailReadResult> results = new ArrayList<>();
            for (int index = count; index >= start; index--) {
                Message message = folder.getMessage(index);
                Date receivedAt = message.getReceivedDate();
                if (minReceivedAt != null && (receivedAt == null || receivedAt.before(minReceivedAt))) {
                    continue;
                }
                MailReadResult result = parseMatchedMessage(message, folderName);
                if (StringUtils.isBlank(expectedParseType) || expectedParseType.equals(result.getParseType())) {
                    results.add(result);
                }
            }
            return results;
        } catch (MessagingException e) {
            log.warn("read mail folder failed, folder={}", folderName, e);
            return List.of();
        } finally {
            closeQuietly(folder);
        }
    }

    private GlobalMailReadBatch readRecentFromGlobalFolder(Store store, String folderName, GlobalMailUidCursor cursor) throws Exception {
        Folder folder = null;
        try {
            folder = store.getFolder(folderName);
            if (folder == null || !folder.exists()) {
                log.debug("global mail folder does not exist, folder={}", folderName);
                return null;
            }
            folder.open(Folder.READ_ONLY);

            int count = folder.getMessageCount();
            if (count <= 0) {
                return null;
            }
            if (folder instanceof UIDFolder uidFolder) {
                return readRecentFromUidFolder(uidFolder, folderName, count, cursor);
            }
            List<MailReadResult> results = readRecentMessagesByIndex(folder, folderName, properties.getGlobalMaxScanCount());
            return buildGlobalMailReadBatch(folderName, results, null, null, false);
        } catch (MessagingException e) {
            log.warn("read global mail folder failed, folder={}", folderName, e);
            return null;
        } finally {
            closeQuietly(folder);
        }
    }

    private GlobalMailReadBatch readRecentFromUidFolder(
        UIDFolder uidFolder,
        String folderName,
        int messageCount,
        GlobalMailUidCursor cursor
    ) throws Exception {
        long uidValidity = uidFolder.getUIDValidity();
        if (isValidUidCursor(cursor, uidValidity)) {
            Message[] messages = uidFolder.getMessagesByUID(cursor.getLastUid() + 1, UIDFolder.LASTUID);
            List<MailReadResult> results = parseUidMessages(uidFolder, folderName, uidValidity, messages);
            return buildGlobalMailReadBatch(folderName, results, uidValidity, maxUid(results, cursor.getLastUid()), true);
        }

        List<MailReadResult> results = readRecentMessagesByIndex((Folder) uidFolder, folderName, properties.getGlobalMaxScanCount());
        for (MailReadResult result : results) {
            result.setImapUidValidity(uidValidity);
        }
        return buildGlobalMailReadBatch(folderName, results, uidValidity, maxUid(results, null), false);
    }

    private boolean isValidUidCursor(GlobalMailUidCursor cursor, long uidValidity) {
        return cursor != null
            && cursor.getUidValidity() != null
            && cursor.getLastUid() != null
            && cursor.getLastUid() > 0
            && cursor.getUidValidity() == uidValidity;
    }

    private List<MailReadResult> parseUidMessages(
        UIDFolder uidFolder,
        String folderName,
        long uidValidity,
        Message[] messages
    ) throws Exception {
        if (messages == null || messages.length == 0) {
            return List.of();
        }
        List<MailReadResult> results = new ArrayList<>();
        for (int index = messages.length - 1; index >= 0; index--) {
            Message message = messages[index];
            MailReadResult result = parseMatchedMessage(message, folderName);
            attachUid(result, uidFolder, message, uidValidity);
            results.add(result);
        }
        return deduplicateRecentResults(results);
    }

    private List<MailReadResult> readRecentMessagesByIndex(Folder folder, String folderName, int maxScanCount) throws Exception {
        int count = folder.getMessageCount();
        int start = Math.max(1, count - Math.max(1, maxScanCount) + 1);
        List<MailReadResult> results = new ArrayList<>();
        UIDFolder uidFolder = folder instanceof UIDFolder uidSource ? uidSource : null;
        long uidValidity = uidFolder == null ? 0L : uidFolder.getUIDValidity();
        for (int index = count; index >= start; index--) {
            Message message = folder.getMessage(index);
            MailReadResult result = parseMatchedMessage(message, folderName);
            if (uidFolder != null) {
                attachUid(result, uidFolder, message, uidValidity);
            }
            results.add(result);
        }
        return deduplicateRecentResults(results);
    }

    private void attachUid(MailReadResult result, UIDFolder uidFolder, Message message, long uidValidity) throws MessagingException {
        if (result == null || uidFolder == null || message == null) {
            return;
        }
        long uid = uidFolder.getUID(message);
        if (uid > 0) {
            result.setImapUid(uid);
            result.setImapUidValidity(uidValidity);
        }
    }

    private Long maxUid(List<MailReadResult> results, Long defaultValue) {
        Long maxUid = defaultValue;
        if (results != null) {
            for (MailReadResult result : results) {
                Long uid = result == null ? null : result.getImapUid();
                if (uid != null && (maxUid == null || uid > maxUid)) {
                    maxUid = uid;
                }
            }
        }
        return maxUid;
    }

    private GlobalMailReadBatch buildGlobalMailReadBatch(
        String folderName,
        List<MailReadResult> results,
        Long uidValidity,
        Long nextLastUid,
        boolean cursorMode
    ) {
        GlobalMailReadBatch batch = new GlobalMailReadBatch();
        batch.setFolderName(folderName);
        batch.setResults(results == null ? List.of() : results);
        batch.setUidValidity(uidValidity);
        batch.setNextLastUid(nextLastUid);
        batch.setCursorMode(cursorMode);
        return batch;
    }

    private List<MailReadResult> deduplicateRecentResults(List<MailReadResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        Map<String, MailReadResult> deduplicated = new LinkedHashMap<>();
        results.stream()
            .sorted(Comparator.comparing(TicketMailReaderService.MailReadResult::getReceivedAt,
                Comparator.nullsLast(Date::compareTo)).reversed())
            .forEach(result -> deduplicated.putIfAbsent(mailIdentityKey(result), result));
        return new ArrayList<>(deduplicated.values());
    }

    private String mailIdentityKey(MailReadResult result) {
        if (result == null) {
            return "";
        }
        if (StringUtils.isNotBlank(result.getMessageId())) {
            String recipients = result.getRecipientEmails() == null ? "" : String.join(",", result.getRecipientEmails());
            return "mid:" + result.getMessageId() + ":" + recipients;
        }
        long receivedAt = result.getReceivedAt() == null ? 0L : result.getReceivedAt().getTime();
        return "fallback:" + receivedAt + ":" + StringUtils.defaultString(result.getSubject());
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
        MailReadResult result = parseMailContent(
            subject,
            body,
            buildBodyExcerpt(body),
            extractFromAddress(message),
            message.getReceivedDate(),
            firstHeader(message, "Message-ID"),
            folderName
        );
        List<String> recipientEmails = extractRecipientEmails(message);
        result.setRecipientEmails(recipientEmails);
        result.setOriginalRecipientEmail(recipientEmails.isEmpty() ? null : recipientEmails.get(0));
        return result;
    }

    private MailReadResult parseMailContent(
        String subject,
        String body,
        String bodyExcerpt,
        String fromAddress,
        Date receivedAt,
        String messageId,
        String folderName
    ) {
        String normalizedSubject = decodeText(subject);
        String rawBody = StringUtils.defaultString(body);
        String textBody = stripHtml(rawBody).replaceAll("\\s+", " ").trim();
        String lotteryApplicationNo = extractLotteryApplicationNo(textBody);
        String activationUrl = extractActivationUrl(rawBody);
        String verifyCode = StringUtils.isBlank(activationUrl) ? extractVerifyCode(rawBody) : null;

        MailReadResult result = new MailReadResult();
        result.setSubject(normalizedSubject);
        result.setFromAddress(fromAddress);
        result.setReceivedAt(receivedAt);
        result.setMessageId(messageId);
        result.setFolderName(folderName);
        result.setBodyExcerpt(bodyExcerpt);
        result.setBodyContent(textBody);

        if (isLivePocketLotterySelectedMail(normalizedSubject, textBody)) {
            result.setParsed(true);
            result.setParseType(PARSE_TYPE_LOTTERY_SELECTED);
            result.setLotteryApplicationNo(lotteryApplicationNo);
            result.setLotteryResultStatus(LOTTERY_RESULT_SELECTED);
            result.setActivationUrl(null);
            result.setVerifyCode(null);
            result.setMessage("解析到抽选当选邮件");
        } else if (isLivePocketLotteryRejectedMail(normalizedSubject, textBody)) {
            result.setParsed(true);
            result.setParseType(PARSE_TYPE_LOTTERY_REJECTED);
            result.setLotteryApplicationNo(lotteryApplicationNo);
            result.setLotteryResultStatus(LOTTERY_RESULT_REJECTED);
            result.setActivationUrl(null);
            result.setVerifyCode(null);
            result.setMessage("解析到抽选落选邮件");
        } else if (isLivePocketLotteryAppliedMail(normalizedSubject, textBody)) {
            result.setParsed(true);
            result.setParseType(PARSE_TYPE_LOTTERY_APPLIED);
            result.setLotteryApplicationNo(lotteryApplicationNo);
            result.setLotteryResultStatus(LOTTERY_RESULT_APPLIED);
            result.setActivationUrl(null);
            result.setVerifyCode(null);
            result.setMessage("解析到抽选申请完成邮件");
        } else if (isLivePocketPurchaseCompletedMail(normalizedSubject, textBody, lotteryApplicationNo)) {
            result.setParsed(true);
            result.setParseType(PARSE_TYPE_PURCHASE_COMPLETED);
            result.setLotteryApplicationNo(lotteryApplicationNo);
            result.setLotteryResultStatus(null);
            result.setActivationUrl(null);
            result.setVerifyCode(null);
            result.setMessage("解析到购入完成邮件");
        } else if (StringUtils.isNotBlank(activationUrl)) {
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
            result.setLotteryApplicationNo(null);
            result.setLotteryResultStatus(null);
            result.setMessage("未解析到抽选、验证码或激活信息");
        }
        return result;
    }

    private String extractLotteryApplicationNo(String textBody) {
        Matcher matcher = LOTTERY_APPLICATION_NO_PATTERN.matcher(StringUtils.defaultString(textBody));
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean isLivePocketLotterySelectedMail(String subject, String textBody) {
        String normalizedSubject = StringUtils.defaultString(subject);
        String normalizedBody = StringUtils.defaultString(textBody);
        return normalizedSubject.contains("[LivePocket]抽選結果のお知らせ")
            && normalizedBody.contains("ご当選されました");
    }

    private boolean isLivePocketLotteryRejectedMail(String subject, String textBody) {
        String normalizedSubject = StringUtils.defaultString(subject);
        String normalizedBody = StringUtils.defaultString(textBody);
        return normalizedSubject.contains("[LivePocket]抽選結果のお知らせ")
            && (normalizedBody.contains("落選となりました") || normalizedBody.contains("残念ながら落選"));
    }

    private boolean isLivePocketLotteryAppliedMail(String subject, String textBody) {
        String normalizedSubject = StringUtils.defaultString(subject);
        String normalizedBody = StringUtils.defaultString(textBody);
        return normalizedSubject.contains("[LivePocket]抽選申込完了のお知らせ")
            || normalizedSubject.contains("抽選申し込みを受け付けました")
            || normalizedBody.contains("抽選申し込みを受け付けました")
            || normalizedBody.contains("お申し込みを受け付けました")
            || normalizedBody.contains("受付番号")
            || normalizedBody.contains("抽選受付番号")
            || normalizedBody.contains("チケットの申込みが完了しました")
            || normalizedBody.contains("抽選申込が完了しました")
            || normalizedBody.contains("抽選結果は、当選発表予定日以降");
    }

    private boolean isLivePocketPurchaseCompletedMail(String subject, String textBody, String applicationNo) {
        if (StringUtils.isBlank(applicationNo)) {
            return false;
        }
        String normalizedSubject = StringUtils.defaultString(subject);
        String normalizedBody = StringUtils.defaultString(textBody);
        return (normalizedSubject.contains("購入完了") || normalizedBody.contains("購入が完了しました")
            || normalizedBody.contains("チケットの購入が完了しました"))
            && (normalizedSubject.contains("LivePocket") || normalizedBody.contains("LivePocket"))
            && normalizedBody.contains("申込番号");
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

    private List<String> extractRecipientEmails(Message message) {
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        if (message == null) {
            return List.of();
        }
        List<String> headers = properties.getGlobalRecipientHeaders();
        if (headers != null) {
            for (String header : headers) {
                if (StringUtils.isBlank(header)) {
                    continue;
                }
                try {
                    addEmailsFromHeaderValues(emails, message.getHeader(header));
                } catch (MessagingException ignored) {
                }
            }
        }
        addEmailsFromRecipients(emails, message, Message.RecipientType.TO);
        addEmailsFromRecipients(emails, message, Message.RecipientType.CC);
        addEmailsFromRecipients(emails, message, Message.RecipientType.BCC);
        return new ArrayList<>(emails);
    }

    private void addEmailsFromRecipients(Set<String> emails, Message message, Message.RecipientType recipientType) {
        try {
            Address[] recipients = message.getRecipients(recipientType);
            if (recipients == null) {
                return;
            }
            for (Address recipient : recipients) {
                addAddressEmail(emails, recipient);
            }
        } catch (MessagingException ignored) {
        }
    }

    private void addEmailsFromHeaderValues(Set<String> emails, String[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        for (String value : values) {
            addEmailsFromText(emails, decodeText(value));
        }
    }

    private void addEmailsFromText(Set<String> emails, String text) {
        String value = StringUtils.trimToEmpty(text);
        if (StringUtils.isBlank(value)) {
            return;
        }
        int semicolonIndex = value.indexOf(';');
        if (semicolonIndex >= 0 && semicolonIndex < value.length() - 1) {
            value = value.substring(semicolonIndex + 1);
        }
        try {
            for (InternetAddress address : InternetAddress.parseHeader(value, false)) {
                addAddressEmail(emails, address);
            }
        } catch (Exception ignored) {
            Matcher matcher = EMAIL_PATTERN.matcher(value);
            while (matcher.find()) {
                addNormalizedEmail(emails, matcher.group());
            }
        }
    }

    private void addAddressEmail(Set<String> emails, Address address) {
        if (address == null) {
            return;
        }
        if (address instanceof InternetAddress internetAddress) {
            addNormalizedEmail(emails, internetAddress.getAddress());
            return;
        }
        addEmailsFromText(emails, address.toString());
    }

    private void addNormalizedEmail(Set<String> emails, String email) {
        String normalized = StringUtils.trimToEmpty(email).toLowerCase(Locale.ROOT);
        if (EMAIL_PATTERN.matcher(normalized).matches()) {
            emails.add(normalized);
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
        while (contextMatcher.find()) {
            String code = contextMatcher.group(1);
            if (isPlausibleVerifyCode(text, contextMatcher.start(1), contextMatcher.end(1), code)) {
                return code;
            }
        }
        Matcher anyMatcher = ANY_CODE_PATTERN.matcher(text);
        while (anyMatcher.find()) {
            String code = anyMatcher.group(1);
            if (isPlausibleVerifyCode(text, anyMatcher.start(1), anyMatcher.end(1), code)) {
                return code;
            }
        }
        return null;
    }

    private boolean isPlausibleVerifyCode(String text, int start, int end, String code) {
        if (StringUtils.isBlank(code) || YEAR_PATTERN.matcher(code).matches()) {
            return false;
        }
        String context = text.substring(Math.max(0, start - 16), Math.min(text.length(), end + 16));
        if (context.contains("申込番号") || context.contains("受付番号") || context.contains("抽選受付番号")) {
            return false;
        }
        char before = start > 0 ? text.charAt(start - 1) : '\0';
        char after = end < text.length() ? text.charAt(end) : '\0';
        if (before == '/' || before == '-' || before == '年' || before == ':' || before == '：') {
            return false;
        }
        return after != '/' && after != '-' && after != '年' && after != '月' && after != '日' && after != ':' && after != '：';
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
        private String bodyContent;
        private String lotteryApplicationNo;
        private String lotteryResultStatus;
        private String message;
        private String originalRecipientEmail;
        private List<String> recipientEmails = List.of();
        private Long imapUid;
        private Long imapUidValidity;
    }

    @Data
    public static class GlobalMailUidCursor implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long uidValidity;
        private Long lastUid;
    }

    @Data
    public static class GlobalMailReadBatch implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String folderName;
        private List<MailReadResult> results = List.of();
        private Long uidValidity;
        private Long nextLastUid;
        private boolean cursorMode;
    }
}
