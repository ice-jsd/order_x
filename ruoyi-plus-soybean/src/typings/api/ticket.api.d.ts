declare namespace Api {
  namespace Ticket {
    type RelationStatus =
      | 'available'
      | 'registering'
      | 'registered'
      | 'register_failed'
      | 'logged_in'
      | 'login_failed'
      | 'blocked';

    type BatchStatus = 'draft' | 'executing' | 'completed' | 'partial' | 'blocked';

    type Platform = Common.CommonTenantRecord<{
      platformId: CommonType.IdType;
      platformCode: string;
      platformName: string;
      adapterType: string;
      environment: string;
      enabled: boolean;
      supportsBatchRegister: boolean;
      supportsBatchLogin: boolean;
      supportsSms: boolean;
      supportsEmail: boolean;
      supportsPhoneIdentity: boolean;
      callbackUrl: string;
      callbackSecretMask: string;
      registrationTemplate: string;
      loginStrategy: string;
      remark?: string;
    }>;

    type PlatformSearchParams = CommonType.RecordNullable<
      Pick<Platform, 'platformCode' | 'platformName' | 'enabled'> & Api.Common.CommonSearchParams
    >;

    type PlatformOperateParams = CommonType.RecordNullable<{
      platformId: CommonType.IdType;
      platformCode: string;
      platformName: string;
      adapterType: string;
      environment: string;
      enabled: boolean;
      supportsBatchRegister: boolean;
      supportsBatchLogin: boolean;
      supportsSms: boolean;
      supportsEmail: boolean;
      supportsPhoneIdentity: boolean;
      callbackUrl: string;
      callbackSecretMask: string;
      registrationTemplate: string;
      loginStrategy: string;
      remark: string;
    }>;

    type PlatformList = Api.Common.PaginatingQueryRecord<Platform>;

    type Phone = Common.CommonTenantRecord<{
      phoneId: CommonType.IdType;
      phoneNumber: string;
      countryCode: string;
      supplier: string;
      status: string;
      note?: string;
      registeredPlatformCount: number;
      loggedInPlatformCount: number;
    }>;

    type PhoneSearchParams = CommonType.RecordNullable<
      Pick<Phone, 'phoneNumber' | 'countryCode' | 'supplier' | 'status'> & Api.Common.CommonSearchParams
    >;

    type PhoneRegisterableSearchParams = CommonType.RecordNullable<
      Pick<Phone, 'phoneNumber' | 'countryCode'> & Api.Common.CommonSearchParams
    >;

    type PhoneImportParams = CommonType.RecordNullable<{
      supplier: string;
      countryCode: string;
      status: string;
      note: string;
      numbers: string;
    }>;

    type PhoneStatusParams = {
      phoneIds: CommonType.IdType[];
      status: string;
    };

    type PhoneImportResult = {
      totalCount: number;
      importedCount: number;
      skippedCount: number;
      skippedNumbers: string[];
    };

    type PhoneList = Api.Common.PaginatingQueryRecord<Phone>;

    type Relation = Common.CommonTenantRecord<{
      relationId: CommonType.IdType;
      phoneId: CommonType.IdType;
      platformId: CommonType.IdType;
      accountId: CommonType.IdType;
      status: RelationStatus | string;
      lastError?: string;
      lastOperateTime: string;
      phoneNumber: string;
      platformName: string;
      accountNo: string;
    }>;

    type RelationSearchParams = CommonType.RecordNullable<
      Pick<Relation, 'phoneId' | 'platformId' | 'accountId' | 'status'> & Api.Common.CommonSearchParams
    >;

    type RelationList = Api.Common.PaginatingQueryRecord<Relation>;

    type Account = Common.CommonTenantRecord<{
      accountId: CommonType.IdType;
      platformId: CommonType.IdType;
      phoneId: CommonType.IdType;
      accountNo: string;
      displayName: string;
      accountStatus: string;
      loginStatus: string;
      sessionExpireTime: string;
      lastLoginTime: string;
      lastError?: string;
      platformName: string;
      phoneNumber: string;
    }>;

    type AccountSearchParams = CommonType.RecordNullable<
      Pick<Account, 'platformId' | 'phoneId' | 'accountNo' | 'displayName' | 'accountStatus' | 'loginStatus'> &
        Api.Common.CommonSearchParams
    >;

    type AccountList = Api.Common.PaginatingQueryRecord<Account>;

    type BatchRegisterParams = CommonType.RecordNullable<{
      phoneIds: CommonType.IdType[];
    }>;

    type BatchLoginParams = CommonType.RecordNullable<{
      accountIds: CommonType.IdType[];
      loginStatus: string;
    }>;

    type RegistrationBatch = Common.CommonTenantRecord<{
      batchId: CommonType.IdType;
      platformId: CommonType.IdType;
      batchNo: string;
      batchStatus: BatchStatus | string;
      totalCount: number;
      successCount: number;
      skippedCount: number;
      failedCount: number;
      resultSummary: string;
      executedAt: string;
      platformName: string;
    }>;

    type RegistrationBatchDetail = Common.CommonTenantRecord<{
      detailId: CommonType.IdType;
      batchId: CommonType.IdType;
      phoneId: CommonType.IdType;
      platformId: CommonType.IdType;
      executeStatus: 'processing' | 'success' | 'failed' | 'skipped' | string;
      resultMessage: string;
      accountId?: CommonType.IdType;
      accountNo?: string;
      executedAt: string;
      phoneNumber?: string;
      platformName?: string;
    }>;

    type RegistrationBatchSearchParams = CommonType.RecordNullable<
      Pick<RegistrationBatch, 'platformId' | 'batchNo' | 'batchStatus'> & Api.Common.CommonSearchParams
    >;

    type RegistrationBatchList = Api.Common.PaginatingQueryRecord<RegistrationBatch>;

    type LoginBatch = Common.CommonTenantRecord<{
      batchId: CommonType.IdType;
      platformId: CommonType.IdType;
      batchNo: string;
      batchStatus: BatchStatus | string;
      totalCount: number;
      successCount: number;
      failedCount: number;
      resultSummary: string;
      executedAt: string;
      platformName: string;
    }>;

    type LoginBatchDetail = Common.CommonTenantRecord<{
      detailId: CommonType.IdType;
      batchId: CommonType.IdType;
      accountId: CommonType.IdType;
      platformId: CommonType.IdType;
      executeStatus: 'processing' | 'success' | 'failed' | string;
      resultMessage: string;
      sessionToken?: string;
      sessionExpireTime?: string;
      executedAt: string;
      accountNo?: string;
      phoneNumber?: string;
      platformName?: string;
    }>;

    type LoginBatchSearchParams = CommonType.RecordNullable<
      Pick<LoginBatch, 'platformId' | 'batchNo' | 'batchStatus'> & Api.Common.CommonSearchParams
    >;

    type LoginBatchList = Api.Common.PaginatingQueryRecord<LoginBatch>;

    type Event = Common.CommonTenantRecord<{
      eventId: CommonType.IdType;
      platformId: CommonType.IdType;
      eventCode: string;
      eventName: string;
      saleTime: string;
      eventStatus: string;
      inventoryPolicy: string;
      remark?: string;
      platformName: string;
    }>;

    type EventSearchParams = CommonType.RecordNullable<
      Pick<Event, 'platformId' | 'eventCode' | 'eventName' | 'eventStatus'> & Api.Common.CommonSearchParams
    >;

    type EventOperateParams = CommonType.RecordNullable<{
      eventId: CommonType.IdType;
      platformId: CommonType.IdType;
      eventCode: string;
      eventName: string;
      saleTime: string;
      eventStatus: string;
      inventoryPolicy: string;
      remark: string;
    }>;

    type EventList = Api.Common.PaginatingQueryRecord<Event>;

    type SaleTask = Common.CommonTenantRecord<{
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      eventId: CommonType.IdType;
      taskName: string;
      taskMode: string;
      taskStatus: string;
      warmupTime: string;
      scheduledTime: string;
      lastExecutedTime: string;
      ruleConfig: string;
      remark?: string;
      platformName: string;
      eventName: string;
    }>;

    type SaleTaskSearchParams = CommonType.RecordNullable<
      Pick<SaleTask, 'platformId' | 'eventId' | 'taskName' | 'taskMode' | 'taskStatus'> & Api.Common.CommonSearchParams
    >;

    type SaleTaskOperateParams = CommonType.RecordNullable<{
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      eventId: CommonType.IdType;
      taskName: string;
      taskMode: string;
      taskStatus: string;
      warmupTime: string;
      scheduledTime: string;
      ruleConfig: string;
      remark: string;
    }>;

    type SaleTaskList = Api.Common.PaginatingQueryRecord<SaleTask>;

    type OrderExecution = Common.CommonTenantRecord<{
      executionId: CommonType.IdType;
      taskId: CommonType.IdType;
      platformId: CommonType.IdType;
      accountId: CommonType.IdType;
      orderNo: string;
      executionStatus: string;
      resultMessage: string;
      executedAt: string;
      platformName: string;
      accountNo: string;
      taskName: string;
    }>;

    type OrderExecutionSearchParams = CommonType.RecordNullable<
      Pick<OrderExecution, 'taskId' | 'platformId' | 'accountId' | 'orderNo' | 'executionStatus'> &
        Api.Common.CommonSearchParams
    >;

    type OrderExecutionList = Api.Common.PaginatingQueryRecord<OrderExecution>;

    type AuditLog = Common.CommonTenantRecord<{
      auditId: CommonType.IdType;
      moduleName: string;
      actionType: string;
      businessType: string;
      businessKey: string;
      auditStatus: string;
      message: string;
      payload: string;
      eventTime: string;
    }>;

    type AuditLogSearchParams = CommonType.RecordNullable<
      Pick<AuditLog, 'moduleName' | 'actionType' | 'businessType' | 'auditStatus'> & Api.Common.CommonSearchParams
    >;

    type AuditLogList = Api.Common.PaginatingQueryRecord<AuditLog>;

    type RegisterProgressMessage = {
      module: 'ticket_register';
      batchId: CommonType.IdType;
      platformId: CommonType.IdType;
      platformName: string;
      phoneId?: CommonType.IdType;
      phoneNumber?: string;
      stepStatus: 'processing' | 'success' | 'failed' | 'skipped' | 'completed' | string;
      phoneStatus?: string;
      note?: string;
      accountId?: CommonType.IdType;
      accountNo?: string;
      message: string;
      successCount: number;
      failedCount: number;
      skippedCount: number;
      processedCount: number;
      totalCount: number;
      registeredPlatformCount?: number;
      loggedInPlatformCount?: number;
    };

    type LoginProgressMessage = {
      module: 'ticket_login';
      batchId: CommonType.IdType;
      platformId: CommonType.IdType;
      platformName: string;
      accountId?: CommonType.IdType;
      phoneId?: CommonType.IdType;
      accountNo?: string;
      phoneNumber?: string;
      stepStatus: 'processing' | 'success' | 'failed' | 'completed' | string;
      loginStatus?: string;
      lastError?: string;
      sessionExpireTime?: string;
      lastLoginTime?: string;
      message: string;
      successCount: number;
      failedCount: number;
      processedCount: number;
      totalCount: number;
    };
  }
}
