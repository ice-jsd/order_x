UPDATE `ticket_mail_record`
SET `parse_type` = 'lottery_applied',
    `parsed` = 1,
    `verify_code` = NULL,
    `activation_url` = NULL,
    `lottery_application_no` = REGEXP_REPLACE(
      REGEXP_SUBSTR(
        CONCAT_WS('\n', `subject`, `body_content`, `body_excerpt`),
        '(抽選受付番号|受付番号|申込番号)[[:space:]]*([：:]|\\[|【|\\(|（)?[[:space:]]*[0-9]+[[:space:]]*(\\]|】|\\)|）)?'
      ),
      '[^0-9]',
      ''
    )
WHERE `del_flag` = 0
  AND `subject` LIKE '%HANDS DO%'
  AND `subject` LIKE '%抽選申し込みを受け付けました%'
  AND REGEXP_LIKE(
    CONCAT_WS('\n', `subject`, `body_content`, `body_excerpt`),
    '(抽選受付番号|受付番号|申込番号)[[:space:]]*([：:]|\\[|【|\\(|（)?[[:space:]]*[0-9]+[[:space:]]*(\\]|】|\\)|）)?'
  );

UPDATE `ticket_mailbox_account` mailbox
JOIN `ticket_mail_record` record
  ON record.`mailbox_id` = mailbox.`mailbox_id`
 AND record.`tenant_id` = mailbox.`tenant_id`
 AND record.`del_flag` = 0
LEFT JOIN `ticket_mail_record` newer
  ON newer.`mailbox_id` = record.`mailbox_id`
 AND newer.`tenant_id` = record.`tenant_id`
 AND newer.`del_flag` = 0
 AND (
      COALESCE(newer.`received_at`, '1970-01-01 00:00:00') > COALESCE(record.`received_at`, '1970-01-01 00:00:00')
      OR (
           COALESCE(newer.`received_at`, '1970-01-01 00:00:00') = COALESCE(record.`received_at`, '1970-01-01 00:00:00')
       AND newer.`record_id` > record.`record_id`
      )
 )
SET mailbox.`latest_verify_code` = NULL,
    mailbox.`latest_activation_url` = NULL
WHERE newer.`record_id` IS NULL
  AND record.`parse_type` = 'lottery_applied'
  AND record.`subject` LIKE '%HANDS DO%'
  AND record.`subject` LIKE '%抽選申し込みを受け付けました%';
