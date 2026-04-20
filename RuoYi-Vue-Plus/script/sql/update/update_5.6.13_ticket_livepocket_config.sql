-- LivePocket 平台切换到真实四步下单适配器
UPDATE ticket_platform_config
SET adapter_type = 'livepocket',
    order_submit_url = 'https://livepocket.jp/purchase',
    supports_email = 1,
    supports_phone_identity = 1,
    enabled = 1,
    remark = 'LivePocket真实四步下单配置，请在抢购任务task_options中提供ticketsPageUrl等参数'
WHERE platform_code = 'livepocket'
  AND del_flag = 0;
