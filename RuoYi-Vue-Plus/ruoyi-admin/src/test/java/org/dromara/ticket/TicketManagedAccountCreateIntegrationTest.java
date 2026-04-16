package org.dromara.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.DromaraApplication;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ticket.domain.TicketManagedAccount;
import org.dromara.ticket.domain.TicketPhoneNumber;
import org.dromara.ticket.domain.TicketPhonePlatformRelation;
import org.dromara.ticket.domain.TicketPlatformConfig;
import org.dromara.ticket.domain.bo.TicketManagedAccountCreateBo;
import org.dromara.ticket.mapper.TicketManagedAccountMapper;
import org.dromara.ticket.mapper.TicketPhoneNumberMapper;
import org.dromara.ticket.mapper.TicketPhonePlatformRelationMapper;
import org.dromara.ticket.mapper.TicketPlatformConfigMapper;
import org.dromara.ticket.service.ITicketOpsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = DromaraApplication.class)
@ActiveProfiles("dev")
@Transactional
class TicketManagedAccountCreateIntegrationTest {

    @Autowired
    private ITicketOpsService ticketOpsService;

    @Autowired
    private TicketPlatformConfigMapper platformMapper;

    @Autowired
    private TicketPhoneNumberMapper phoneMapper;

    @Autowired
    private TicketPhonePlatformRelationMapper relationMapper;

    @Autowired
    private TicketManagedAccountMapper accountMapper;

    @Test
    void createManagedAccount_shouldCreateAccountAndRegisteredRelation() {
        long seed = System.currentTimeMillis();
        long platformId = 881000000000000000L + seed;
        long phoneId = 881000000100000000L + seed;

        insertPlatform(platformId, "account-create-" + seed, "账号创建平台");
        insertPhone(phoneId, "0901" + seed, "available");

        TicketManagedAccountCreateBo bo = createBo(platformId, phoneId, "create-" + seed + "@test.local");
        ticketOpsService.createManagedAccount(bo);

        TicketManagedAccount account = accountMapper.selectOne(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getEmail, bo.getEmail()));
        assertNotNull(account);
        assertEquals(phoneId, account.getPhoneId());
        assertEquals("registered", account.getAccountStatus());
        assertEquals("offline", account.getLoginStatus());
        assertNull(account.getLastLoginTime());
        assertNull(account.getLastError());

        TicketPhonePlatformRelation relation = relationMapper.selectOne(new LambdaQueryWrapper<TicketPhonePlatformRelation>()
            .eq(TicketPhonePlatformRelation::getPlatformId, platformId)
            .eq(TicketPhonePlatformRelation::getPhoneId, phoneId));
        assertNotNull(relation);
        assertEquals(account.getAccountId(), relation.getAccountId());
        assertEquals("registered", relation.getStatus());
        assertNull(relation.getLastError());
        assertNotNull(relation.getLastOperateTime());
    }

    @Test
    void createManagedAccount_shouldRejectDuplicateEmailInSamePlatform() {
        long seed = System.currentTimeMillis();
        long platformId = 882000000000000000L + seed;
        long phoneId = 882000000100000000L + seed;
        long existingAccountId = 882000000200000000L + seed;
        String email = "duplicate-" + seed + "@test.local";

        insertPlatform(platformId, "account-duplicate-" + seed, "重复邮箱平台");
        insertPhone(phoneId, "0911" + seed, "available");
        insertAccount(existingAccountId, platformId, phoneId, email);

        TicketManagedAccountCreateBo bo = createBo(platformId, phoneId, email);
        ServiceException ex = assertThrows(ServiceException.class, () -> ticketOpsService.createManagedAccount(bo));
        assertEquals("同平台下邮箱已存在", ex.getMessage());
    }

    @Test
    void createManagedAccount_shouldRejectWhenPhoneHasActiveRelation() {
        long seed = System.currentTimeMillis();
        long platformId = 883000000000000000L + seed;
        long phoneId = 883000000100000000L + seed;
        long relationId = 883000000200000000L + seed;

        insertPlatform(platformId, "account-active-relation-" + seed, "有效关系平台");
        insertPhone(phoneId, "0921" + seed, "available");
        insertRelation(relationId, platformId, phoneId, 883000000300000000L + seed, "registered");

        TicketManagedAccountCreateBo bo = createBo(platformId, phoneId, "active-" + seed + "@test.local");
        ServiceException ex = assertThrows(ServiceException.class, () -> ticketOpsService.createManagedAccount(bo));
        assertEquals("该号码在当前平台已存在有效关系", ex.getMessage());
    }

    @Test
    void createManagedAccount_shouldReuseInactiveRelation() {
        long seed = System.currentTimeMillis();
        long platformId = 884000000000000000L + seed;
        long phoneId = 884000000100000000L + seed;
        long relationId = 884000000200000000L + seed;
        long oldAccountId = 884000000300000000L + seed;

        insertPlatform(platformId, "account-reuse-" + seed, "复用关系平台");
        insertPhone(phoneId, "0931" + seed, "available");
        insertRelation(relationId, platformId, phoneId, oldAccountId, "blocked");

        TicketManagedAccountCreateBo bo = createBo(platformId, phoneId, "reuse-" + seed + "@test.local");
        ticketOpsService.createManagedAccount(bo);

        TicketManagedAccount newAccount = accountMapper.selectOne(new LambdaQueryWrapper<TicketManagedAccount>()
            .eq(TicketManagedAccount::getPlatformId, platformId)
            .eq(TicketManagedAccount::getEmail, bo.getEmail()));
        assertNotNull(newAccount);

        TicketPhonePlatformRelation relation = relationMapper.selectById(relationId);
        assertNotNull(relation);
        assertEquals(newAccount.getAccountId(), relation.getAccountId());
        assertEquals("registered", relation.getStatus());
        assertNull(relation.getLastError());
    }

    private TicketManagedAccountCreateBo createBo(Long platformId, Long phoneId, String email) {
        TicketManagedAccountCreateBo bo = new TicketManagedAccountCreateBo();
        bo.setPlatformId(platformId);
        bo.setPhoneId(phoneId);
        bo.setEmail(email);
        bo.setAccountInfo("{\"nickname\":\"manual-account\"}");
        bo.setReqData("{\"channel\":\"manual\"}");
        return bo;
    }

    private void insertPlatform(Long platformId, String platformCode, String platformName) {
        TicketPlatformConfig platform = new TicketPlatformConfig();
        platform.setPlatformId(platformId);
        platform.setPlatformCode(platformCode);
        platform.setPlatformName(platformName);
        platform.setAdapterType("mock");
        platform.setEnvironment("sandbox");
        platform.setEnabled(Boolean.TRUE);
        platform.setSupportsBatchRegister(Boolean.FALSE);
        platform.setSupportsBatchLogin(Boolean.FALSE);
        platform.setSupportsSms(Boolean.FALSE);
        platform.setSupportsEmail(Boolean.TRUE);
        platform.setSupportsPhoneIdentity(Boolean.TRUE);
        platform.setCreateTime(new Date());
        platform.setUpdateTime(new Date());
        platform.setDelFlag(0L);
        platformMapper.insert(platform);
    }

    private void insertPhone(Long phoneId, String phoneNumber, String status) {
        TicketPhoneNumber phone = new TicketPhoneNumber();
        phone.setPhoneId(phoneId);
        phone.setPhoneNumber(phoneNumber);
        phone.setCountryCode("JP+81");
        phone.setSupplier("manual-test");
        phone.setStatus(status);
        phone.setNote("test phone");
        phone.setCreateTime(new Date());
        phone.setUpdateTime(new Date());
        phone.setDelFlag(0L);
        phoneMapper.insert(phone);
    }

    private void insertRelation(Long relationId, Long platformId, Long phoneId, Long accountId, String status) {
        TicketPhonePlatformRelation relation = new TicketPhonePlatformRelation();
        relation.setRelationId(relationId);
        relation.setPlatformId(platformId);
        relation.setPhoneId(phoneId);
        relation.setAccountId(accountId);
        relation.setStatus(status);
        relation.setLastError("history");
        relation.setLastOperateTime(new Date());
        relation.setCreateTime(new Date());
        relation.setUpdateTime(new Date());
        relation.setDelFlag(0L);
        relationMapper.insert(relation);
    }

    private void insertAccount(Long accountId, Long platformId, Long phoneId, String email) {
        TicketManagedAccount account = new TicketManagedAccount();
        account.setAccountId(accountId);
        account.setPlatformId(platformId);
        account.setPhoneId(phoneId);
        account.setEmail(email);
        account.setAccountInfo("{\"nickname\":\"existing\"}");
        account.setReqData("{\"channel\":\"seed\"}");
        account.setAccountStatus("registered");
        account.setLoginStatus("offline");
        account.setCreateTime(new Date());
        account.setUpdateTime(new Date());
        account.setDelFlag(0L);
        accountMapper.insert(account);
    }
}
