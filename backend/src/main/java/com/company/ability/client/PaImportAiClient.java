package com.company.ability.client;

import com.company.ability.config.PaImportAiProperties;
import com.company.ability.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages API 兼容网关客户端（能力结构导入专用）。
 *
 * <p>与 hr-report 的 {@code AnthropicGatewayClient} 同构（那边已验证过网关行为与重试策略），
 * 但<b>不复用它的类</b>：跨项目共享代码要建独立的构件，为一个 30 行的 HTTP 封装不值当。</p>
 *
 * <p>三条实测结论写进了默认值（2026-11-21 探活）：</p>
 * <ul>
 *   <li>单次调用 18~25 秒 → 读超时 120 秒，且<b>调用方必须是异步线程</b>，不能让 HTTP 请求干等</li>
 *   <li>推理过程占用输出额度 → {@code max-tokens} 默认 16384；给 2000 时输出是空串</li>
 *   <li>网关偶发 503 → 429/5xx 重试两次</li>
 * </ul>
 */
@Component
public class PaImportAiClient {

    private static final Logger log = LoggerFactory.getLogger(PaImportAiClient.class);

    /** 网关偶发 5xx/限流（实测 503 常见）：重试两次即可恢复。 */
    private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504);
    private static final int MAX_ATTEMPTS = 3;

    private final PaImportAiProperties props;
    private final ObjectMapper json;
    private final RestTemplate http;

    public PaImportAiClient(PaImportAiProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(3, props.getConnectTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(10, props.getTimeoutSeconds())));
        this.http = new RestTemplate(factory);
    }

    /** 是否可用。不可用时调用方降级到手工映射，而不是抛错阻断整条导入链 */
    public boolean available() {
        return props.configured();
    }

    /** 单轮补全，返回所有 text block 拼接后的文本（忽略思考块）。 */
    public String complete(String system, String user) {
        if (!props.configured()) {
            throw new BusinessException("AI 网关未配置：请设置 ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN / ANTHROPIC_MODEL"
                    + "（或 backend/config/secret.yml 的 pa.import.ai.*）");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("anthropic-version", "2023-06-01");
        headers.set("x-api-key", props.getApiKey());
        headers.setBearerAuth(props.getApiKey());

        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                // 推理模型的思考过程也计入输出额度；给太小会在思考中途被截断
                "max_tokens", props.getMaxTokens() > 0 ? props.getMaxTokens() : 16384,
                "temperature", 0,
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user)));

        String url = stripTrailingSlash(props.getBaseUrl()) + "/v1/messages";
        BusinessException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String raw = http.postForObject(url, new HttpEntity<>(body, headers), String.class);
                return extractText(raw);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                last = new BusinessException("AI 网关返回 " + status + "：" + truncate(e.getResponseBodyAsString(), 300));
                if (!RETRYABLE.contains(status) || attempt == MAX_ATTEMPTS) {
                    throw last;
                }
                log.warn("AI 网关 {}（第 {}/{} 次），稍后重试", status, attempt, MAX_ATTEMPTS);
            } catch (BusinessException e) {
                // 输出截断/解析失败属于内容问题，重试同一请求没有意义。
                throw e;
            } catch (Exception e) {
                last = new BusinessException("AI 网关调用失败：" + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw last;
                }
                log.warn("AI 网关调用异常（{}，第 {}/{} 次），稍后重试", e.getMessage(), attempt, MAX_ATTEMPTS);
            }
            backoff(attempt);
        }
        throw last;
    }

    /**
     * 要求模型返回 JSON，并把它解析成指定类型。
     *
     * <p>网关返回的文本可能带 markdown 围栏（{@code ```json ... ```}）或前后有解释文字，
     * 这里做一次「剥围栏 + 截取首个 JSON 对象」的容错。仍然解析不了就报可读错误，
     * 由调用方降级 —— 不在这里无限重试，因为重试同样的提示词结果通常一样。</p>
     */
    public <T> T completeJson(String system, String user, Class<T> type) {
        String text = complete(system, user);
        String cleaned = stripToJson(text);
        try {
            return json.readValue(cleaned, type);
        } catch (Exception e) {
            log.warn("模型输出不是合法 JSON：{}", truncate(text, 400));
            throw new BusinessException("模型返回的内容读不懂（不是合法 JSON），请重试一次。"
                    + "如果反复出现，可以在页面上切到手工映射。");
        }
    }

    /** 剥 markdown 围栏、截取最外层 JSON 对象 */
    public static String stripToJson(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            if (firstLine > 0) {
                s = s.substring(firstLine + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        return s.trim();
    }

    /** 2s → 5s：足够跨过网关的瞬时抖动，又不会把异步任务拖太久。 */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(attempt == 1 ? 2000L : 5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractText(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if ("max_tokens".equals(root.path("stop_reason").asText(""))) {
                throw new BusinessException("模型输出被截断（max_tokens=" + props.getMaxTokens()
                        + "，思考过程占满额度）；请重试或调大 pa.import.ai.max-tokens");
            }
            JsonNode content = root.path("content");
            if (!content.isArray()) {
                throw new BusinessException("AI 网关响应缺少 content：" + truncate(raw, 200));
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                String text = block.path("text").asText("");
                if (text.isEmpty()) {
                    continue;
                }
                // 只取 text block：thinking 块也带 text，混进来会污染 JSON
                if (type.isEmpty() || "text".equals(type)) {
                    sb.append(text);
                }
            }
            String out = sb.toString().trim();
            if (out.isEmpty()) {
                throw new BusinessException("AI 网关只返回了思考过程，没有可用文本（可调大 pa.import.ai.max-tokens）");
            }
            return out;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("AI 网关响应解析失败：" + e.getMessage());
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
