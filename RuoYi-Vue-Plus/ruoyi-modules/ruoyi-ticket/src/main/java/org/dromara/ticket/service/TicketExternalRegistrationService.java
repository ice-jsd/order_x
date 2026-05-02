package org.dromara.ticket.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ticket.config.TicketSmsProviderProperties;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketMailboxAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPhonePlatformRelation;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.bo.TicketExternalActivationConfirmBo;
import org.dromara.ticket.domain.bo.TicketExternalRegistrationConfirmBo;
import org.dromara.ticket.domain.vo.TicketExternalRegisterAccountVo;
import org.dromara.ticket.domain.vo.TicketExternalSmsCodeVo;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketMailboxAccountMapper;
import org.dromara.ticket.mapper.TicketPhoneNumberMapper;
import org.dromara.ticket.mapper.TicketPhonePlatformRelationMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class TicketExternalRegistrationService {

    private static final String ACCOUNT_PENDING_REGISTER = "pending_register";
    private static final String ACCOUNT_PENDING_ACTIVATION = "pending_activation";
    private static final String ACCOUNT_ACTIVATED = "activated";
    private static final List<String> ACTIVE_RELATION_STATUSES = List.of(
        "registering", "verification_pending", "registered", "logged_in"
    );
    private static final Pattern CODE_PATTERN = Pattern.compile("(?<!\\d)([0-9]{4,8})(?!\\d)");
    private static final DateTimeFormatter DEFAULT_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> FAMILY_NAMES = List.of(
        "佐藤", "鈴木", "高橋", "田中", "伊藤", "渡辺", "山本", "中村", "小林", "加藤",
        "吉田", "山田", "佐々木", "山口", "松本", "井上", "木村", "林", "清水", "斎藤"
    );
    private static final List<String> GIVEN_NAMES = List.of(
        "太郎", "次郎", "健太", "翔太", "大輔", "拓也", "直樹", "悠人", "陽斗", "蓮",
        "花子", "美咲", "愛子", "結衣", "葵", "さくら", "陽菜", "美月", "優奈", "莉子"
    );
    private static final List<String> RESIDENCES = List.of(
        "東京都", "大阪府", "京都府", "神奈川県", "埼玉県", "千葉県", "兵庫県", "愛知県", "福岡県", "北海道",
        "宮城県", "広島県", "静岡県", "茨城県", "栃木県", "群馬県", "奈良県", "岡山県", "熊本県", "沖縄県"
    );

    private final TicketPlatformConfigMapper platformMapper;
    private final TicketManagedAccountMapper accountMapper;
    private final TicketMailboxAccountMapper mailboxMapper;
    private final TicketPhoneNumberMapper phoneMapper;
    private final TicketPhonePlatformRelationMapper relationMapper;
    private final ITicketMailboxAccountService mailboxAccountService;
    private final TicketSmsProviderClient smsProviderClient;
    private final TicketSmsProviderProperties smsProperties;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public R<TicketExternalRegisterAccountVo> nextRegister(String platformCode) {
        TicketPlatformConfig platform = requirePlatformByCode(platformCode);

        TicketMailboxAccount mailbox = selectAvailableMailbox(platform.getPlatformId());
        PhoneLease phoneLease = acquirePhoneLease(platform.getPlatformId());
        String phoneNumber = phoneLease.phoneNumber();
        int countdown = phoneLease.countdown();
        Date leaseExpireTime = new Date(System.currentTimeMillis() + countdown * 1000L);
        TicketPhoneNumber phone = getOrCreatePhone(phoneNumber);

        String platformPassword = mailbox.getUsername() + "@ABC";
        GeneratedProfile profile = generateProfile();
        TicketManagedAccount account = new TicketManagedAccount();
        account.setPlatformId(platform.getPlatformId());
        account.setPhoneId(phone.getPhoneId());
        account.setEmail(mailbox.getEmail());
        account.setAccountInfo(toJson(buildAccountInfo(profile, platformPassword)));
        account.setReqData(toJson(buildReqData(mailbox, phone, phoneLease, leaseExpireTime, countdown)));
        account.setAccountStatus(ACCOUNT_PENDING_REGISTER);
        account.setLoginStatus("offline");
        account.setLastLoginTime(null);
        account.setLastError(null);
        accountMapper.insert(account);

        int mailboxRows = mailboxMapper.update(null, Wrappers.<TicketMailboxAccount>lambdaUpdate()
            .eq(TicketMailboxAccount::getMailboxId, mailbox.getMailboxId())
            .eq(TicketMailboxAccount::getStatus, "available")
            .set(TicketMailboxAccount::getStatus, "used")
            .set(TicketMailboxAccount::getUsedAccountId, account.getAccountId())
            .set(TicketMailboxAccount::getUsedTime, new Date())
            .set(TicketMailboxAccount::getLastError, null));
        if (mailboxRows <= 0) {
            throw new ServiceException("邮箱账号已被占用，请重试");
        }

        TicketPhonePlatformRelation relation = relationMapper.selectOne(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, platform.getPlatformId())
            .eq(TicketPhonePlatformRelation::getPhoneId, phone.getPhoneId()), false);
        if (relation == null) {
            relation = new TicketPhonePlatformRelation();
        }
        relation.setPhoneId(phone.getPhoneId());
        relation.setPlatformId(platform.getPlatformId());
        relation.setAccountId(account.getAccountId());
        relation.setStatus("registering");
        relation.setLastError(null);
        relation.setLastOperateTime(new Date());
        if (relation.getRelationId() == null) {
            relationMapper.insert(relation);
        } else {
            relationMapper.updateById(relation);
        }

        TicketExternalRegisterAccountVo vo = new TicketExternalRegisterAccountVo();
        vo.setAccountId(account.getAccountId());
        vo.setEmail(account.getEmail());
        vo.setPassword(platformPassword);
        vo.setConfirmPassword(platformPassword);
        vo.setFamilyName(profile.familyName());
        vo.setGivenName(profile.givenName());
        vo.setGender(profile.gender());
        vo.setBirthYear(profile.birthYear());
        vo.setBirthMonth(profile.birthMonth());
        vo.setBirthDay(profile.birthDay());
        vo.setCountryRegion("日本");
        vo.setPhoneNumber(phoneNumber);
        vo.setResidence(profile.residence());
        vo.setLanguage("日本語");
        vo.setPhoneLeaseExpireTime(leaseExpireTime);
        vo.setPhoneCountdownSeconds(countdown);
        return R.ok(vo);
    }

    @Transactional(rollbackFor = Exception.class)
    public R<Void> confirmRegister(TicketExternalRegistrationConfirmBo bo) {
        TicketManagedAccount account = requireAccount(bo.getPlatformCode(), bo.getEmail());
        Date now = new Date();
        if (Boolean.TRUE.equals(bo.getSuccess())) {
            updateAccountAfterCallback(account, ACCOUNT_PENDING_ACTIVATION, bo.getAccountInfo(), bo.getReqData(), null);
            updateRelation(account, "verification_pending", null, now);
            return R.ok();
        }
        String message = StrUtil.blankToDefault(bo.getMessage(), "注册失败");
        updateAccountAfterCallback(account, ACCOUNT_PENDING_REGISTER, bo.getAccountInfo(), bo.getReqData(), message);
        updateRelation(account, "register_failed", message, now);
        return R.fail(message);
    }

    @Transactional(rollbackFor = Exception.class)
    public R<TicketExternalSmsCodeVo> getPhoneActivationCode(String platformCode, String email) {
        if (!smsProperties.isEnabled()) {
            return R.warn("短信平台未配置，当前随机手机号无法获取验证码");
        }
        TicketManagedAccount account = requireAccount(platformCode, email);
        Map<String, Object> reqData = parseObject(account.getReqData());
        String phoneNumber = stringValue(reqData.get("phoneNumber"));
        if (StrUtil.isBlank(phoneNumber) && account.getPhoneId() != null) {
            TicketPhoneNumber phone = phoneMapper.selectById(account.getPhoneId());
            phoneNumber = phone == null ? null : phone.getPhoneNumber();
        }
        if (StrUtil.isBlank(phoneNumber)) {
            return R.fail("账号未绑定手机号");
        }
        Date leaseExpireTime = parseDate(reqData.get("phoneLeaseExpireTime"));
        boolean phoneRenewed = false;
        if (leaseExpireTime != null && leaseExpireTime.before(new Date())) {
            try {
                PhoneLease renewedLease = reacquirePhoneLease(account, phoneNumber);
                account = accountMapper.selectById(account.getAccountId());
                reqData = parseObject(account.getReqData());
                phoneNumber = renewedLease.phoneNumber();
                leaseExpireTime = parseDate(reqData.get("phoneLeaseExpireTime"));
                phoneRenewed = true;
            } catch (ServiceException ex) {
                TicketExternalSmsCodeVo vo = buildSmsWaitVo(phoneNumber, leaseExpireTime, reqData, false);
                return R.warn("手机号倒计时已过，重新取同一号码失败，请稍后重试：" + ex.getMessage(), vo);
            }
        }

        TicketSmsProviderClient.SmsListResult smsList;
        try {
            smsList = smsProviderClient.querySmsList(phoneNumber);
        } catch (ServiceException ex) {
            TicketExternalSmsCodeVo vo = buildSmsWaitVo(phoneNumber, leaseExpireTime, reqData, phoneRenewed);
            return R.warn("短信平台查询验证码超时或异常，请稍后重试：" + ex.getMessage(), vo);
        }
        TicketSmsProviderClient.SmsMessage latest = latestMessage(smsList.getMessages());
        if (latest == null || StrUtil.isBlank(latest.getSmsctx())) {
            TicketExternalSmsCodeVo vo = buildSmsWaitVo(phoneNumber, leaseExpireTime, reqData, phoneRenewed);
            return R.warn(phoneRenewed ? "手机号已重新取号，请使用新手机号继续等待验证码" : "暂未收到短信", vo);
        }
        String code = extractCode(latest.getSmsctx());
        if (StrUtil.isBlank(code)) {
            TicketExternalSmsCodeVo vo = buildSmsWaitVo(phoneNumber, leaseExpireTime, reqData, phoneRenewed);
            vo.setSmsText(latest.getSmsctx());
            vo.setReceivedAt(latest.getSmstime());
            return R.warn("短信未解析到验证码", vo);
        }
        TicketExternalSmsCodeVo vo = new TicketExternalSmsCodeVo();
        vo.setVerifyCode(code);
        vo.setSmsText(latest.getSmsctx());
        vo.setReceivedAt(latest.getSmstime());
        vo.setPhoneNumber(phoneNumber);
        vo.setPhoneLeaseExpireTime(leaseExpireTime);
        vo.setPhoneCountdownSeconds(intValue(reqData.get("phoneCountdownSeconds")));
        vo.setPhoneRenewed(phoneRenewed);
        return R.ok(vo);
    }

    @Transactional(rollbackFor = Exception.class)
    public R<Void> confirmActivate(TicketExternalActivationConfirmBo bo) {
        TicketManagedAccount account = requireAccount(bo.getPlatformCode(), bo.getEmail());
        Date now = new Date();
        if (Boolean.TRUE.equals(bo.getSuccess())) {
            updateAccountAfterCallback(account, ACCOUNT_ACTIVATED, bo.getAccountInfo(), bo.getReqData(), null);
            updateRelation(account, "registered", null, now);
            return R.ok();
        }
        String message = StrUtil.blankToDefault(bo.getMessage(), "激活失败");
        updateAccountAfterCallback(account, ACCOUNT_PENDING_ACTIVATION, bo.getAccountInfo(), bo.getReqData(), message);
        updateRelation(account, "verification_pending", message, now);
        return R.fail(message);
    }

    private void assertFixedEquipAvailable(Long platformId) {
        if (!smsProperties.isEnabled()) {
            return;
        }
        List<TicketManagedAccount> pendingAccounts = accountMapper.selectList(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .in(TicketManagedAccount::getAccountStatus, List.of(ACCOUNT_PENDING_REGISTER, ACCOUNT_PENDING_ACTIVATION))
            .orderByDesc(TicketManagedAccount::getAccountId));
        Date now = new Date();
        for (TicketManagedAccount account : pendingAccounts) {
            Map<String, Object> reqData = parseObject(account.getReqData());
            String equipno = stringValue(reqData.get("smsEquipno"));
            Date expireTime = parseDate(reqData.get("phoneLeaseExpireTime"));
            if (StrUtil.equals(equipno, smsProperties.getEquipno()) && expireTime != null && expireTime.after(now)) {
                throw new ServiceException("当前短信设备号码仍在使用中，请等待释放倒计时结束");
            }
        }
    }

    private void assertProviderEquipReleased() {
        TicketSmsProviderClient.SmsEquipResult equip = smsProviderClient.queryCurrentEquip();
        int countdown = resolveLeaseSeconds(equip);
        if (countdown > 0 && StrUtil.isNotBlank(equip.getMsisdn())) {
            throw new ServiceException("当前短信设备号码仍在释放倒计时，请稍后再试");
        }
    }

    private PhoneLease acquirePhoneLease(Long platformId) {
        if (!smsProperties.isEnabled()) {
            return mockPhoneLease(platformId);
        }
        // 取新号前先尽力释放/屏蔽设备上一个号码；供应商释放接口不稳定时，不阻塞后续取号。
        smsProviderClient.releaseCurrentPhoneBestEffort();
        TicketSmsProviderClient.SmsPhoneResult phoneResult = smsProviderClient.getNewPhone();
        TicketSmsProviderClient.SmsEquipResult equipResult = smsProviderClient.queryCurrentEquip(phoneResult.getMsisdn());
        String phoneNumber = StrUtil.blankToDefault(equipResult.getMsisdn(), phoneResult.getMsisdn());
        if (StrUtil.isBlank(phoneNumber)) {
            throw new ServiceException("短信平台未返回手机号，请稍后重试");
        }
        if (phoneExistsInPlatform(platformId, phoneNumber)) {
            throw new ServiceException("短信平台返回的手机号已在当前平台注册使用，请等待释放后重试");
        }
        int countdown = resolveLeaseSeconds(equipResult);
        return new PhoneLease(
            phoneNumber,
            StrUtil.blankToDefault(equipResult.getEquipno(), smsProperties.getEquipno()),
            StrUtil.blankToDefault(equipResult.getAppid(), smsProperties.getAppid()),
            phoneResult.getReqid(),
            countdown,
            safeJson(phoneResult.getRawData()),
            safeJson(equipResult.getRawData()),
            false
        );
    }

    private PhoneLease reacquirePhoneLease(TicketManagedAccount account, String expiredPhoneNumber) {
        if (!smsProperties.isEnabled()) {
            throw new ServiceException("短信平台未启用，无法重新取号");
        }
        TicketSmsProviderClient.SmsPhoneResult phoneResult = smsProviderClient.getNewPhone(expiredPhoneNumber);
        TicketSmsProviderClient.SmsEquipResult equipResult = smsProviderClient.queryCurrentEquip(phoneResult.getMsisdn());
        String phoneNumber = StrUtil.blankToDefault(equipResult.getMsisdn(), phoneResult.getMsisdn());
        if (StrUtil.isBlank(phoneNumber)) {
            throw new ServiceException("短信平台重新取号未返回手机号，请稍后重试");
        }
        if (phoneExistsInPlatform(account.getPlatformId(), phoneNumber, account.getAccountId())) {
            throw new ServiceException("短信平台重新返回的手机号已被当前平台其他账号占用，请稍后重试");
        }
        int countdown = resolveLeaseSeconds(equipResult);
        PhoneLease phoneLease = new PhoneLease(
            phoneNumber,
            StrUtil.blankToDefault(equipResult.getEquipno(), smsProperties.getEquipno()),
            StrUtil.blankToDefault(equipResult.getAppid(), smsProperties.getAppid()),
            phoneResult.getReqid(),
            countdown,
            safeJson(phoneResult.getRawData()),
            safeJson(equipResult.getRawData()),
            false
        );
        saveRenewedPhoneLease(account, phoneLease);
        return phoneLease;
    }

    private void saveRenewedPhoneLease(TicketManagedAccount account, PhoneLease phoneLease) {
        TicketPhoneNumber newPhone = getOrCreatePhone(phoneLease.phoneNumber());
        Long oldPhoneId = account.getPhoneId();
        Date now = new Date();
        Date leaseExpireTime = new Date(System.currentTimeMillis() + Math.max(0, phoneLease.countdown()) * 1000L);

        Map<String, Object> reqData = parseObject(account.getReqData());
        reqData.put("smsMock", phoneLease.mock());
        reqData.put("smsEquipno", phoneLease.equipno());
        reqData.put("smsAppid", phoneLease.appid());
        reqData.put("phoneId", newPhone.getPhoneId());
        reqData.put("phoneNumber", newPhone.getPhoneNumber());
        reqData.put("phoneLeaseExpireTime", leaseExpireTime);
        reqData.put("phoneCountdownSeconds", phoneLease.countdown());
        reqData.put("smsRequestId", phoneLease.requestId());
        reqData.put("smsGetNewPhone", phoneLease.getNewPhonePayload());
        reqData.put("smsEquip", phoneLease.equipPayload());
        reqData.put("phoneRenewedAt", now);

        accountMapper.update(null, Wrappers.<TicketManagedAccount>lambdaUpdate()
            .eq(TicketManagedAccount::getAccountId, account.getAccountId())
            .set(TicketManagedAccount::getPhoneId, newPhone.getPhoneId())
            .set(TicketManagedAccount::getReqData, toJson(reqData))
            .set(TicketManagedAccount::getLastError, null));

        if (oldPhoneId != null && !Objects.equals(oldPhoneId, newPhone.getPhoneId())) {
            relationMapper.update(null, Wrappers.<TicketPhonePlatformRelation>lambdaUpdate()
                .eq(TicketPhonePlatformRelation::getPlatformId, account.getPlatformId())
                .eq(TicketPhonePlatformRelation::getPhoneId, oldPhoneId)
                .eq(TicketPhonePlatformRelation::getAccountId, account.getAccountId())
                .set(TicketPhonePlatformRelation::getStatus, "register_failed")
                .set(TicketPhonePlatformRelation::getLastError, "手机号租约过期，已重新取号")
                .set(TicketPhonePlatformRelation::getLastOperateTime, now));
        }

        TicketPhonePlatformRelation relation = relationMapper.selectOne(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, account.getPlatformId())
            .eq(TicketPhonePlatformRelation::getPhoneId, newPhone.getPhoneId()), false);
        if (relation == null) {
            relation = new TicketPhonePlatformRelation();
            relation.setPlatformId(account.getPlatformId());
            relation.setPhoneId(newPhone.getPhoneId());
            relation.setAccountId(account.getAccountId());
            relation.setStatus("verification_pending");
            relation.setLastError(null);
            relation.setLastOperateTime(now);
            relationMapper.insert(relation);
            return;
        }
        relationMapper.update(null, Wrappers.<TicketPhonePlatformRelation>lambdaUpdate()
            .eq(TicketPhonePlatformRelation::getRelationId, relation.getRelationId())
            .set(TicketPhonePlatformRelation::getAccountId, account.getAccountId())
            .set(TicketPhonePlatformRelation::getStatus, "verification_pending")
            .set(TicketPhonePlatformRelation::getLastError, null)
            .set(TicketPhonePlatformRelation::getLastOperateTime, now));
    }

    private boolean providerStillHoldsPhone(String phoneNumber) {
        try {
            TicketSmsProviderClient.SmsEquipResult equip = smsProviderClient.queryCurrentEquip(phoneNumber);
            return StrUtil.equals(phoneNumber, equip.getMsisdn());
        } catch (Exception ex) {
            log.warn("query provider phone lease failed, phoneNumber={}", phoneNumber, ex);
            return false;
        }
    }

    private int resolveLeaseSeconds(TicketSmsProviderClient.SmsEquipResult equip) {
        int countdown = Math.max(0, Objects.requireNonNullElse(equip.getCountdown(), 0));
        if (countdown > 0) {
            return countdown;
        }
        // 部分短信平台把 countdown 返回成时间字符串而不是秒数；只要设备还持有手机号，
        // 就用配置里的兜底租约，避免刚取号就被本地误判为过期。
        if (StrUtil.isNotBlank(equip.getMsisdn()) && StrUtil.isNotBlank(equip.getCountdownText())) {
            return Math.max(60, smsProperties.getLeaseFallbackSeconds());
        }
        return 0;
    }

    private PhoneLease mockPhoneLease(Long platformId) {
        String phoneNumber;
        int attempts = 0;
        do {
            attempts++;
            phoneNumber = "070" + RandomUtil.randomNumbers(8);
            if (attempts > 100) {
                throw new ServiceException("随机日本手机号生成失败，请重试");
            }
        } while (phoneExistsInPlatform(platformId, phoneNumber));
        return new PhoneLease(
            phoneNumber,
            "mock-random-jp",
            "mock",
            "mock-" + System.currentTimeMillis(),
            600,
            Map.of("mock", true, "phoneNumber", phoneNumber),
            Map.of("mock", true, "countdown", 600),
            true
        );
    }

    private TicketPlatformConfig requirePlatformByCode(String platformCode) {
        List<TicketPlatformConfig> platforms = platformMapper.selectList(new LambdaQueryWrapper<TicketPlatformConfig>()
            .eq(TicketPlatformConfig::getPlatformCode, platformCode)
            .last("limit 2"));
        if (platforms.isEmpty()) {
            throw new ServiceException("平台不存在");
        }
        if (platforms.size() > 1) {
            throw new ServiceException("平台编码重复：" + platformCode);
        }
        return platforms.get(0);
    }

    private TicketManagedAccount requireAccount(String platformCode, String email) {
        TicketPlatformConfig platform = requirePlatformByCode(platformCode);
        TicketManagedAccount account = accountMapper.selectOne(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platform.getPlatformId())
            .eq(TicketManagedAccount::getEmail, email), false);
        if (account == null) {
            throw new ServiceException("账号不存在");
        }
        return account;
    }

    private TicketMailboxAccount selectAvailableMailbox(Long platformId) {
        TicketMailboxAccount mailbox = findAvailableMailbox(platformId);
        if (mailbox != null) {
            return mailbox;
        }
        mailbox = mailboxAccountService.createAvailableMailbox();
        if (!emailExistsInPlatform(platformId, mailbox.getEmail())) {
            return mailbox;
        }
        mailbox = findAvailableMailbox(platformId);
        if (mailbox != null) {
            return mailbox;
        }
        throw new ServiceException("自动创建的新邮箱已被当前平台占用，请重试");
    }

    private TicketMailboxAccount findAvailableMailbox(Long platformId) {
        List<TicketMailboxAccount> mailboxes = mailboxMapper.selectList(new LambdaQueryWrapper<TicketMailboxAccount>()
            .eq(TicketMailboxAccount::getStatus, "available")
            .orderByAsc(TicketMailboxAccount::getMailboxId)
            .last("limit 20"));
        for (TicketMailboxAccount mailbox : mailboxes) {
            if (!emailExistsInPlatform(platformId, mailbox.getEmail())) {
                return mailbox;
            }
        }
        return null;
    }

    private TicketPhoneNumber getOrCreatePhone(String phoneNumber) {
        TicketPhoneNumber phone = phoneMapper.selectOne(new LambdaQueryWrapper<TicketPhoneNumber>()
            .eq(TicketPhoneNumber::getPhoneNumber, phoneNumber), false);
        if (phone != null) {
            return phone;
        }
        phone = new TicketPhoneNumber();
        phone.setPhoneNumber(phoneNumber);
        phone.setCountryCode("+81");
        phone.setSupplier("sms-provider");
        phone.setStatus("available");
        phone.setNote("外部自动注册取号");
        phoneMapper.insert(phone);
        return phone;
    }

    private boolean emailExistsInPlatform(Long platformId, String email) {
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getEmail, email));
        return count != null && count > 0;
    }

    private boolean phoneExistsInPlatform(Long platformId, String phoneNumber) {
        return phoneExistsInPlatform(platformId, phoneNumber, null);
    }

    private boolean phoneExistsInPlatform(Long platformId, String phoneNumber, Long ignoreAccountId) {
        List<TicketPhoneNumber> phones = phoneMapper.selectList(new LambdaQueryWrapper<TicketPhoneNumber>()
            .select(TicketPhoneNumber::getPhoneId)
            .eq(TicketPhoneNumber::getPhoneNumber, phoneNumber)
            .last("limit 20"));
        if (phones.isEmpty()) {
            return false;
        }
        LambdaQueryWrapper<TicketPhonePlatformRelation> wrapper = new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, platformId)
            .in(TicketPhonePlatformRelation::getPhoneId, phones.stream().map(TicketPhoneNumber::getPhoneId).toList())
            .in(TicketPhonePlatformRelation::getStatus, ACTIVE_RELATION_STATUSES);
        if (ignoreAccountId != null) {
            wrapper.ne(TicketPhonePlatformRelation::getAccountId, ignoreAccountId);
        }
        Long count = relationMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    private void updateAccountAfterCallback(TicketManagedAccount account, String accountStatus, String accountInfo,
                                            String reqData, String lastError) {
        String mergedAccountInfo = mergeJson(account.getAccountInfo(), accountInfo);
        String mergedReqData = mergeJson(account.getReqData(), reqData);
        accountMapper.update(null, new LambdaUpdateWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getAccountId, account.getAccountId())
            .set(TicketManagedAccount::getAccountStatus, accountStatus)
            .set(TicketManagedAccount::getAccountInfo, mergedAccountInfo)
            .set(TicketManagedAccount::getReqData, mergedReqData)
            .set(TicketManagedAccount::getLastError, lastError));
    }

    private void updateRelation(TicketManagedAccount account, String status, String lastError, Date now) {
        if (account.getPhoneId() == null) {
            return;
        }
        TicketPhonePlatformRelation relation = relationMapper.selectOne(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, account.getPlatformId())
            .eq(TicketPhonePlatformRelation::getPhoneId, account.getPhoneId()), false);
        if (relation == null) {
            relation = new TicketPhonePlatformRelation();
            relation.setPlatformId(account.getPlatformId());
            relation.setPhoneId(account.getPhoneId());
            relation.setAccountId(account.getAccountId());
            relation.setStatus(status);
            relation.setLastError(lastError);
            relation.setLastOperateTime(now);
            relationMapper.insert(relation);
            return;
        }
        relationMapper.update(null, Wrappers.<TicketPhonePlatformRelation>lambdaUpdate()
            .eq(TicketPhonePlatformRelation::getRelationId, relation.getRelationId())
            .set(TicketPhonePlatformRelation::getAccountId, account.getAccountId())
            .set(TicketPhonePlatformRelation::getStatus, status)
            .set(TicketPhonePlatformRelation::getLastError, lastError)
            .set(TicketPhonePlatformRelation::getLastOperateTime, now));
    }

    private GeneratedProfile generateProfile() {
        int year = RandomUtil.randomInt(1980, 2007);
        int month = RandomUtil.randomInt(1, 13);
        int maxDay = switch (month) {
            case 2 -> isLeapYear(year) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
        return new GeneratedProfile(
            FAMILY_NAMES.get(RandomUtil.randomInt(FAMILY_NAMES.size())),
            GIVEN_NAMES.get(RandomUtil.randomInt(GIVEN_NAMES.size())),
            RandomUtil.randomBoolean() ? "male" : "female",
            year,
            month,
            RandomUtil.randomInt(1, maxDay + 1),
            RESIDENCES.get(RandomUtil.randomInt(RESIDENCES.size()))
        );
    }

    private boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private Map<String, Object> buildAccountInfo(GeneratedProfile profile, String platformPassword) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("familyName", profile.familyName());
        map.put("givenName", profile.givenName());
        map.put("gender", profile.gender());
        map.put("birthYear", profile.birthYear());
        map.put("birthMonth", profile.birthMonth());
        map.put("birthDay", profile.birthDay());
        map.put("countryRegion", "日本");
        map.put("residence", profile.residence());
        map.put("language", "日本語");
        map.put("platformPassword", platformPassword);
        return map;
    }

    private Map<String, Object> buildReqData(TicketMailboxAccount mailbox, TicketPhoneNumber phone,
                                             PhoneLease phoneLease,
                                             Date leaseExpireTime, int countdown) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mailboxId", mailbox.getMailboxId());
        map.put("mailboxUsername", mailbox.getUsername());
        map.put("smsMock", phoneLease.mock());
        map.put("smsEquipno", phoneLease.equipno());
        map.put("smsAppid", phoneLease.appid());
        map.put("phoneId", phone.getPhoneId());
        map.put("phoneNumber", phone.getPhoneNumber());
        map.put("phoneLeaseExpireTime", leaseExpireTime);
        map.put("phoneCountdownSeconds", countdown);
        map.put("smsRequestId", phoneLease.requestId());
        map.put("smsGetNewPhone", phoneLease.getNewPhonePayload());
        map.put("smsEquip", phoneLease.equipPayload());
        return map;
    }

    private TicketExternalSmsCodeVo buildSmsWaitVo(String phoneNumber, Date leaseExpireTime,
                                                   Map<String, Object> reqData, boolean phoneRenewed) {
        TicketExternalSmsCodeVo vo = new TicketExternalSmsCodeVo();
        vo.setPhoneNumber(phoneNumber);
        vo.setPhoneLeaseExpireTime(leaseExpireTime);
        vo.setPhoneCountdownSeconds(intValue(reqData.get("phoneCountdownSeconds")));
        vo.setPhoneRenewed(phoneRenewed);
        return vo;
    }

    private TicketSmsProviderClient.SmsMessage latestMessage(List<TicketSmsProviderClient.SmsMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }

    private String extractCode(String smsText) {
        Matcher matcher = CODE_PATTERN.matcher(StrUtil.blankToDefault(smsText, ""));
        return matcher.find() ? matcher.group(1) : null;
    }

    private String mergeJson(String oldJson, String patchJson) {
        if (StrUtil.isBlank(patchJson)) {
            return oldJson;
        }
        if (StrUtil.isBlank(oldJson)) {
            return patchJson;
        }
        try {
            Map<String, Object> merged = parseObject(oldJson);
            merged.putAll(parseObject(patchJson));
            return objectMapper.writeValueAsString(merged);
        } catch (Exception ignored) {
            return patchJson;
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return JSONUtil.toJsonStr(value);
        }
    }

    private Object safeJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return json;
        }
    }

    private Date parseDate(Object value) {
        if (value instanceof Date date) {
            return date;
        }
        if (value instanceof Number number) {
            return new Date(number.longValue());
        }
        String text = stringValue(value);
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Date.from(java.time.Instant.parse(text));
        } catch (Exception ignored) {
        }
        try {
            return Date.from(LocalDateTime.parse(text, DEFAULT_DATE_TIME_FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        return StrUtil.isNumeric(text) ? Integer.parseInt(text) : null;
    }

    private record GeneratedProfile(
        String familyName,
        String givenName,
        String gender,
        Integer birthYear,
        Integer birthMonth,
        Integer birthDay,
        String residence
    ) {
    }

    private record PhoneLease(
        String phoneNumber,
        String equipno,
        String appid,
        String requestId,
        int countdown,
        Object getNewPhonePayload,
        Object equipPayload,
        boolean mock
    ) {
    }
}
