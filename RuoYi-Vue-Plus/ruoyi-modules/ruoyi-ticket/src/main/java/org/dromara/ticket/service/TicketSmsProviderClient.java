package org.dromara.ticket.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ticket.config.TicketSmsProviderProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketSmsProviderClient {

    private final TicketSmsProviderProperties properties;
    private final ObjectMapper objectMapper;

    public SmsPhoneResult getNewPhone() {
        return getNewPhone(null);
    }

    public SmsPhoneResult getNewPhone(String msisdn) {
        validateConfig();
        String reqid = newReqid();
        JsonNode data = post("/out_api/get_newphone", reqid, Map.of(
            "equipno", numericIfPossible(properties.getEquipno()),
            "numtype", StrUtil.isBlank(msisdn) ? 0 : 1,
            "msisdn", StrUtil.blankToDefault(msisdn, ""),
            "appid", numericIfPossible(properties.getAppid())
        ));

        SmsPhoneResult result = new SmsPhoneResult();
        result.setReqid(reqid);
        result.setEquipno(text(data, "equipno", properties.getEquipno()));
        result.setEquipstatus(text(data, "equipstatus", null));
        result.setAppid(text(data, "appid", properties.getAppid()));
        result.setMsisdn(text(data, "msisdn", null));
        result.setRawData(data.toString());
        return result;
    }

    public void releaseCurrentPhoneBestEffort() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            SmsEquipResult equip = queryCurrentEquip();
            if (StrUtil.isBlank(equip.getMsisdn())) {
                return;
            }
            releasePhoneBestEffort(equip.getEquipno(), equip.getMsisdn());
        } catch (Exception ex) {
            log.warn("release current sms phone skipped: {}", ex.getMessage());
        }
    }

    public void releasePhoneBestEffort(String equipno, String msisdn) {
        if (StrUtil.hasBlank(equipno, msisdn)) {
            return;
        }
        String reqid = newReqid();
        try {
            post("/out_api/fb_abnormal", reqid, Map.of(
                "equipno", numericIfPossible(equipno),
                "msisdn", msisdn
            ));
            log.info("sms phone released/marked abnormal, equipno={}, msisdn={}", equipno, msisdn);
        } catch (Exception ex) {
            log.warn("sms phone release/abnormal request failed, equipno={}, msisdn={}, reqid={}, error={}",
                equipno, msisdn, reqid, ex.getMessage());
        }
    }

    public SmsEquipResult queryCurrentEquip() {
        return queryCurrentEquip(null);
    }

    public SmsEquipResult queryCurrentEquip(String msisdn) {
        validateConfig();
        String reqid = newReqid();
        JsonNode data = post("/out_api/query_equips", reqid, Map.of(
            "equipnos", List.of()
        ));

        JsonNode devices = data.path("devices");
        JsonNode device = selectDevice(devices, msisdn);
        if (device == null) {
            device = data;
        }
        SmsEquipResult result = new SmsEquipResult();
        result.setReqid(reqid);
        result.setEquipno(text(device, "equipno", properties.getEquipno()));
        result.setEquipstatus(text(device, "equipstatus", null));
        result.setAppid(text(device, "appid", properties.getAppid()));
        result.setMsisdn(text(device, "msisdn", null));
        result.setCountdown(intValue(device, "countdown", 0));
        result.setCountdownText(text(device, "countdown", null));
        result.setLastsmstime(text(device, "lastsmstime", null));
        result.setLastsmsctx(text(device, "lastsmsctx", null));
        result.setRawData(data.toString());
        return result;
    }

    public SmsListResult querySmsList(String msisdn) {
        validateConfig();
        if (StrUtil.isBlank(msisdn)) {
            throw new ServiceException("手机号不能为空");
        }
        String reqid = newReqid();
        JsonNode data = post("/out_api/query_smslists", reqid, Map.of("msisdn", msisdn));

        SmsListResult result = new SmsListResult();
        result.setReqid(reqid);
        result.setMsisdn(text(data, "msisdn", msisdn));
        result.setRawData(data.toString());
        JsonNode smslists = data.path("smslists");
        if (smslists.isArray()) {
            for (JsonNode item : smslists) {
                SmsMessage message = new SmsMessage();
                message.setSmstime(text(item, "smstime", null));
                message.setSmsctx(text(item, "smsctx", null));
                message.setLastsmsappid(text(item, "lastsmsappid", null));
                result.getMessages().add(message);
            }
        }
        return result;
    }

    private JsonNode post(String path, String reqid, Object data) {
        try {
            Map<String, Object> payload = Map.of("reqid", reqid, "data", data);
            String requestBody = objectMapper.writeValueAsString(payload);
            HttpURLConnection connection = openConnection(URI.create(trimRightSlash(properties.getBaseUrl()) + path));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(properties.getConnectTimeoutMs());
            connection.setReadTimeout(properties.getReadTimeoutMs());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("apikey", properties.getApiKey());
            connection.setRequestProperty("sign", sign(reqid));

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new ServiceException("短信平台请求失败：" + statusCode + " " + responseBody);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(root.path("status").asInt(0));
            if (code != 0) {
                String message = root.path("msg").asText(root.path("message").asText("短信平台返回失败"));
                if (code == 10003) {
                    throw new ServiceException("短信平台号码获取中，请稍后重试：" + message);
                }
                throw new ServiceException("短信平台返回失败：" + code + " " + message);
            }
            JsonNode dataNode = root.path("data");
            if (dataNode.isTextual()) {
                return objectMapper.readTree(dataNode.asText());
            }
            return dataNode;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("sms provider request failed, path={}, reqid={}", path, reqid, ex);
            throw new ServiceException("短信平台请求异常：" + ex.getMessage());
        }
    }

    private HttpURLConnection openConnection(URI uri) throws Exception {
        URL url = uri.toURL();
        return (HttpURLConnection) url.openConnection();
    }

    private String readResponseBody(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream inputStream = statusCode >= 200 && statusCode < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void validateConfig() {
        if (!properties.isEnabled()) {
            throw new ServiceException("短信平台未启用");
        }
        if (StrUtil.hasBlank(properties.getBaseUrl(), properties.getApiKey(), properties.getApiSecret(),
            properties.getEquipno(), properties.getAppid())) {
            throw new ServiceException("短信平台配置不完整");
        }
    }

    private String sign(String reqid) throws Exception {
        String text = properties.getApiKey() + "&" + properties.getApiSecret() + "&" + reqid;
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String newReqid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String trimRightSlash(String value) {
        return StrUtil.removeSuffix(value, "/");
    }

    private String text(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asText();
    }

    private int intValue(JsonNode node, String fieldName, int defaultValue) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isNumber()) {
            return value.asInt(defaultValue);
        }
        String text = value.asText();
        return StrUtil.isNumeric(text) ? Integer.parseInt(text) : defaultValue;
    }

    private JsonNode selectDevice(JsonNode devices, String msisdn) {
        if (devices == null || !devices.isArray() || devices.isEmpty()) {
            return null;
        }
        if (StrUtil.isNotBlank(msisdn)) {
            for (JsonNode device : devices) {
                if (StrUtil.equals(msisdn, text(device, "msisdn", null))) {
                    return device;
                }
            }
        }
        for (JsonNode device : devices) {
            if (StrUtil.equals(properties.getEquipno(), text(device, "equipno", null))) {
                return device;
            }
        }
        for (JsonNode device : devices) {
            if (StrUtil.isNotBlank(text(device, "msisdn", null))) {
                return device;
            }
        }
        return devices.get(0);
    }

    private Object numericIfPossible(String value) {
        if (StrUtil.isBlank(value)) {
            return value;
        }
        return StrUtil.isNumeric(value) ? Long.parseLong(value) : value;
    }

    @Data
    public static class SmsPhoneResult {
        private String reqid;
        private String equipno;
        private String equipstatus;
        private String appid;
        private String msisdn;
        private String rawData;
    }

    @Data
    public static class SmsEquipResult {
        private String reqid;
        private String equipno;
        private String equipstatus;
        private String appid;
        private String msisdn;
        private Integer countdown;
        private String countdownText;
        private String lastsmstime;
        private String lastsmsctx;
        private String rawData;
    }

    @Data
    public static class SmsListResult {
        private String reqid;
        private String msisdn;
        private List<SmsMessage> messages = new java.util.ArrayList<>();
        private String rawData;
    }

    @Data
    public static class SmsMessage {
        private String smstime;
        private String smsctx;
        private String lastsmsappid;
    }
}
