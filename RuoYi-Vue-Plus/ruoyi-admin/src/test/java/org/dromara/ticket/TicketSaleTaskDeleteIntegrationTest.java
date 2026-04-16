package org.dromara.ticket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.DromaraApplication;
import org.dromara.ticket.domain.TicketOrderExecution;
import org.dromara.ticket.domain.TicketSaleTask;
import org.dromara.ticket.domain.TicketSaleTaskAccount;
import org.dromara.ticket.mapper.TicketOrderExecutionMapper;
import org.dromara.ticket.mapper.TicketSaleTaskAccountMapper;
import org.dromara.ticket.mapper.TicketSaleTaskMapper;
import org.dromara.ticket.service.ITicketOpsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(classes = DromaraApplication.class)
@ActiveProfiles("dev")
@Transactional
class TicketSaleTaskDeleteIntegrationTest {

    @Autowired
    private ITicketOpsService ticketOpsService;

    @Autowired
    private TicketSaleTaskMapper saleTaskMapper;

    @Autowired
    private TicketSaleTaskAccountMapper saleTaskAccountMapper;

    @Autowired
    private TicketOrderExecutionMapper orderExecutionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void removeSaleTasks_shouldPhysicallyDeleteBindings_andKeepExecutionHistory() {
        long seed = System.currentTimeMillis();
        long taskId = 880000000000000000L + seed;
        long accountId = 880000000100000000L + seed;
        long activeBindingId = 880000000200000000L + seed;
        long deletedBindingId = 880000000300000000L + seed;
        long executionId = 880000000400000000L + seed;

        TicketSaleTask task = new TicketSaleTask();
        task.setTaskId(taskId);
        task.setTenantId("000000");
        task.setPlatformId(9100012L);
        task.setProductId("test-delete-" + seed);
        task.setTaskName("Delete regression task");
        task.setTaskStatus("executing");
        task.setScheduleVersion(1L);
        task.setOrderFlowType("direct_order");
        task.setFulfillmentType("pickup_store");
        task.setPaymentMode("cod_store");
        task.setPurchaseQuantity(1);
        task.setTaskOptions("{}");
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        task.setDelFlag(0L);
        saleTaskMapper.insert(task);

        TicketSaleTaskAccount deletedBinding = new TicketSaleTaskAccount();
        deletedBinding.setBindingId(deletedBindingId);
        deletedBinding.setTenantId("000000");
        deletedBinding.setTaskId(taskId);
        deletedBinding.setAccountId(accountId);
        deletedBinding.setCreateTime(new Date());
        deletedBinding.setUpdateTime(new Date());
        deletedBinding.setDelFlag(1L);
        saleTaskAccountMapper.insert(deletedBinding);

        TicketSaleTaskAccount activeBinding = new TicketSaleTaskAccount();
        activeBinding.setBindingId(activeBindingId);
        activeBinding.setTenantId("000000");
        activeBinding.setTaskId(taskId);
        activeBinding.setAccountId(accountId);
        activeBinding.setCreateTime(new Date());
        activeBinding.setUpdateTime(new Date());
        activeBinding.setDelFlag(0L);
        saleTaskAccountMapper.insert(activeBinding);

        TicketOrderExecution execution = new TicketOrderExecution();
        execution.setExecutionId(executionId);
        execution.setTenantId("000000");
        execution.setTaskId(taskId);
        execution.setPlatformId(9100012L);
        execution.setAccountId(accountId);
        execution.setProductId(task.getProductId());
        execution.setPurchaseQuantity(1);
        execution.setFlowType("direct_order");
        execution.setFulfillmentType("pickup_store");
        execution.setPaymentMode("cod_store");
        execution.setScheduleVersion(1L);
        execution.setCurrentStep("queued");
        execution.setStepStatus("queued");
        execution.setStepTrace("[]");
        execution.setPaymentStatus("manual_pending");
        execution.setExecutionStatus("queued");
        execution.setResultMessage("等待调度");
        execution.setAttemptCount(0);
        execution.setCreateTime(new Date());
        execution.setUpdateTime(new Date());
        execution.setDelFlag(0L);
        orderExecutionMapper.insert(execution);

        assertDoesNotThrow(() -> ticketOpsService.removeSaleTasks(new Long[] { taskId }));

        assertNull(saleTaskMapper.selectById(taskId));
        Integer bindingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ticket_sale_task_account WHERE task_id = ?",
            Integer.class,
            taskId
        );
        assertEquals(0, bindingCount);

        TicketOrderExecution persistedExecution = orderExecutionMapper.selectById(executionId);
        assertNotNull(persistedExecution);
        assertEquals("blocked", persistedExecution.getExecutionStatus());
        assertEquals("任务已删除，执行计划已取消", persistedExecution.getResultMessage());
        assertEquals(
            0L,
            saleTaskAccountMapper.selectCount(new LambdaQueryWrapper<TicketSaleTaskAccount>()
                .eq(TicketSaleTaskAccount::getTaskId, taskId))
        );
    }
}
