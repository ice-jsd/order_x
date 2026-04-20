-- 平台适配器键与平台编码统一

UPDATE `ticket_platform_config`
SET `adapter_type` = `platform_code`
WHERE `platform_code` IN ('livepocket', 'jump-shop-online');
