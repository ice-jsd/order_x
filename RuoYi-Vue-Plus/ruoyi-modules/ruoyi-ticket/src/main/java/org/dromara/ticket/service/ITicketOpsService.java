package org.dromara.ticket.service;

import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.ticket.domain.bo.*;
import org.dromara.ticket.domain.vo.*;

import java.util.Map;

public interface ITicketOpsService {

    TableDataInfo<TicketPlatformConfigVo> selectPlatformPage(TicketPlatformConfigBo bo, PageQuery pageQuery);

    TicketPlatformConfigVo selectPlatformById(Long platformId);

    int savePlatform(TicketPlatformConfigBo bo);

    int updatePlatform(TicketPlatformConfigBo bo);

    int removePlatforms(Long[] platformIds);

    TableDataInfo<TicketPhoneNumberVo> selectPhonePage(TicketPhoneNumberBo bo, PageQuery pageQuery);

    TicketPhoneBulkImportResultVo importPhones(TicketPhoneBulkImportBo bo);

    boolean changePhoneStatus(TicketPhoneStatusBo bo);

    TableDataInfo<TicketPhonePlatformRelationVo> selectRelationPage(TicketPhonePlatformRelationBo bo, PageQuery pageQuery);

    TableDataInfo<TicketPhoneNumberVo> selectRegisterablePhonePage(Long platformId, TicketPhoneNumberBo bo, PageQuery pageQuery);

    R<Long> registerFromPhones(Long platformId, TicketBatchRegisterBo bo);

    TableDataInfo<TicketManagedAccountVo> selectAccountPage(TicketManagedAccountBo bo, PageQuery pageQuery);

    TableDataInfo<TicketManagedAccountVo> selectLoginableAccountPage(Long platformId, TicketManagedAccountBo bo, PageQuery pageQuery);

    R<Long> loginAccounts(Long platformId, TicketBatchLoginBo bo);

    TableDataInfo<TicketRegistrationBatchVo> selectRegistrationBatchPage(TicketRegistrationBatchBo bo, PageQuery pageQuery);

    TicketRegistrationBatchVo selectRegistrationBatchById(Long batchId);

    java.util.List<TicketRegistrationBatchDetailVo> selectRegistrationBatchDetails(Long batchId);

    TableDataInfo<TicketLoginBatchVo> selectLoginBatchPage(TicketLoginBatchBo bo, PageQuery pageQuery);

    TicketLoginBatchVo selectLoginBatchById(Long batchId);

    java.util.List<TicketLoginBatchDetailVo> selectLoginBatchDetails(Long batchId);

    TableDataInfo<TicketEventConfigVo> selectEventPage(TicketEventConfigBo bo, PageQuery pageQuery);

    TicketEventConfigVo selectEventById(Long eventId);

    int saveEvent(TicketEventConfigBo bo);

    int updateEvent(TicketEventConfigBo bo);

    int removeEvents(Long[] eventIds);

    TableDataInfo<TicketSaleTaskVo> selectSaleTaskPage(TicketSaleTaskBo bo, PageQuery pageQuery);

    TicketSaleTaskVo selectSaleTaskById(Long taskId);

    int saveSaleTask(TicketSaleTaskBo bo);

    int updateSaleTask(TicketSaleTaskBo bo);

    int removeSaleTasks(Long[] taskIds);

    R<Long> executeSaleTask(Long taskId);

    TableDataInfo<TicketOrderExecutionVo> selectOrderExecutionPage(TicketOrderExecutionBo bo, PageQuery pageQuery);

    TableDataInfo<TicketAuditEventVo> selectAuditPage(TicketAuditEventBo bo, PageQuery pageQuery);

    R<String> handleCallback(String platformCode, Map<String, Object> payload);
}
