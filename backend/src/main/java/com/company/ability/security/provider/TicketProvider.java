package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 票据模式适配器（对接《三方系统单点登录对接规范》v1.0 §4）。
 *
 * <p><b>与泛微那套的区别，一句话：没有 OAuth2 那一圈。</b>
 * 泛微是「ticket → access_token → profile」三步，票据模式只有两步：
 * authorize 出票、userinfo 拿票换工号。没有 response_type、没有 access_token、
 * 没有 refresh_token、没有 scope —— 这些都是 OAuth2 的东西，对方压根没做。</p>
 *
 * <p><b>为什么不能直接把 WeaverProvider 配一套参数用</b>：不是参数不同，是流程不同。
 * 泛微那边 exchange 要发两次请求，第二次要带上第一次换出来的 access_token；
 * 票据模式只发一次。硬塞进一个类，就会到处出现「本协议没有这一步」的 if。
 * 一个协议一个实现，各自把事实说清楚。</p>
 *
 * <p><b>成功判定看 HTTP 状态，不看响应体</b>：规范 §4.2 约定成功是 200 + 工号本体，
 * 失败是 400/401/404/500 + {@code {code,message}}。跟泛微那种「HTTP 200 里套
 * status/code 判成功」正好相反 —— 所以这里不共用泛微的 isSuccess。</p>
 *
 * <p><b>固定路径全是 null</b>：对方的 authorize / userinfo 地址是各自的应用注册页给的，
 * 没有「站点根 + 固定路径」这回事。所以配置页不会显示认证中心根地址框，
 * 两个完整地址必须填 —— 这是协议的事实，不是偷懒。</p>
 *
 * @see <a href="file:docs/20-三方系统单点登录对接规范.md">20-三方系统单点登录对接规范</a>
 */
@Slf4j
@Component
public class TicketProvider implements IdentityProvider {

    public static final String TYPE = "ticket";

    /**
     * 对方 userinfo 的错误码 → 给人看的中文（规范附录 B）。
     *
     * <p>翻译成中文而不是原样带出：这些码会直接显示在登录失败页上，用户拿着
     * {@code TICKET_EXPIRED} 去找管理员，双方都不知道该改什么；翻成
     * 「票据已失效或已被使用」就自然指向「重新从入口登录」这个动作。</p>
     *
     * <p>不在表里的码用对方给的 {@code message}，再没有就显示原码 ——
     * 宁可显示一个看不懂的码，也不要吞掉对方想说的话。</p>
     */
    private static final Map<String, String> ERROR_MESSAGES = Map.ofEntries(
            Map.entry("TICKET_MISSING", "回调没有带回票据，请重新从入口登录"),
            // 过期与已使用刻意不区分（规范 §4.2）：区分了等于告诉攻击者「这张票刚刚还有效」
            Map.entry("TICKET_EXPIRED", "票据已失效或已被使用，请重新从入口登录"),
            Map.entry("INVALID_CLIENT", "client_id 未在对方系统注册，请联系管理员"),
            Map.entry("INVALID_SECRET", "client_secret 校验失败，请联系管理员检查密钥"),
            Map.entry("USER_NOT_FOUND", "该工号在对方系统中已不存在，请联系管理员"),
            Map.entry("INTERNAL_ERROR", "对方系统内部故障，请稍后重试")
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public TicketProvider(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 与泛微适配器同样的超时。对方是另一套系统，但「连不上就尽快报错」这条没有区别
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(5000))
                .setReadTimeout(Duration.ofMillis(10000))
                .build();
    }

    @Override
    public String providerType() {
        return TYPE;
    }

    // ==================== 本协议的形态 ====================

    /**
     * 两个接口、都必须填完整地址、一次兑换就拿到身份。
     *
     * <p><b>为什么两个地址都标必填且不能推导</b>：对方给的是两个完整 URL，
     * 相互之间没有共同前缀可依赖（可能一个在 {@code /api/sso/}，另一个在
     * {@code /open/sso/}）。写「填根地址自动拼」等于教用户去猜一个不存在的规律。</p>
     */
    @Override
    public SsoShape shape() {
        return new SsoShape(
                List.of(
                        SsoField.required("authorizeUrl", "发起认证地址",
                                "浏览器 302 到这里，由对方判断登录态并出票。"),
                        SsoField.required("tokenUrl", "票据兑换地址",
                                "服务端直连，用票据换工号。")),
                // 没有 access_token 这一步：兑换即得工号
                false,
                // 没有固定路径，根地址推不出任何东西
                false);
    }

    // ==================== 两步 ====================

    /**
     * 拼发起认证地址：{@code authorize?client_id=&redirect_uri=&state=}。
     *
     * <p><b>不带 response_type</b>：那是 OAuth2 告诉授权服务器「我要 code」的参数。
     * 票据模式的 authorize 是普通出票接口，多传一个它没定义过的参数，好的实现会忽略，
     * 严格的实现会直接判参数错误 —— 没有好处，只有风险。</p>
     */
    @Override
    public String buildAuthorizeUrl(ProviderConfig config, String state) {
        String base = requireUrl(config, config.authorizeUrl(), "发起认证地址（sso_authorize_url）");
        StringBuilder sb = new StringBuilder(base);
        char sep = base.indexOf('?') >= 0 ? '&' : '?';
        sb.append(sep).append("client_id=").append(encode(config.clientId()));
        sb.append('&').append("redirect_uri=").append(encode(config.redirectUri()));
        if (state != null && !state.isBlank()) {
            // state 对方会原样带回，本端靠它恢复回跳地址与维度号，不能省
            sb.append('&').append("state=").append(encode(state));
        }
        return sb.toString();
    }

    /**
     * 用票据换工号：一次请求，成功看 HTTP 200。
     *
     * <p><b>请求方式必须配</b>（2026-11-25 起没有默认值）。规范 §4.2 规定的是 GET，
     * 但规范里也留了口子：「如果你们偏好 POST，接口 2 可以改 POST（JSON body），我们配合」。
     * 原来留空即 GET，于是配置页上那一栏永远是空的、看着像没配好；配到只收 POST 的
     * 对方时又要靠人去猜这一栏该怎么填。现在必须在页面上选一个。</p>
     *
     * <p><b>secret 放 query 是 GET 形态的必然</b>。OAuth2 那边尽量把密钥放 body 是因为
     * 它会进对方的访问日志；这里 GET 时无处可放，只能照办，本端日志用
     * {@link #maskUrl} 抹掉密钥和票据。</p>
     */
    @Override
    public String exchange(ProviderConfig config, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(401, "回调没有带回票据，请重新从入口登录");
        }
        String base = requireUrl(config, config.tokenUrl(), "票据兑换地址（sso_token_url）");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());
        params.put("ticket", ticket);

        String method = config.tokenMethod();
        if (method == null || method.isBlank()) {
            throw new BusinessException(500,
                    "维度 " + config.dimensionId() + " 的「兑换请求方式（sso_token_method）」未配置，"
                            + "请到「维度管理 → 集成配置」里选择 GET 或 POST");
        }
        JsonNode response = "POST".equalsIgnoreCase(method.trim())
                ? callAndParsePost(base, params, "兑换票据")
                : callAndParse(withQuery(base, params), "兑换票据");
        return identityOf(config, response);
    }

    // ==================== 内部 ====================

    /**
     * 从成功响应里取工号。
     *
     * <p>字段名必须配（2026-11-25 起不再回退 {@code workcode}）。取不到时报的错要指明字段名 ——
     * 对方把字段叫成 {@code employeeNo} 时，这一句能让人立刻知道该去改哪一栏，
     * 而不是以为对方没返回数据。</p>
     */
    private String identityOf(ProviderConfig config, JsonNode response) {
        String configured = config.userinfoField();
        if (configured == null || configured.isBlank()) {
            throw new BusinessException(500,
                    "维度 " + config.dimensionId() + " 的「用户标识字段（sso_userinfo_field）」未配置，"
                            + "请到「维度管理 → 集成配置」里填写对方返回工号的字段名");
        }
        String field = configured.trim();
        String value = response.path(field).asText(null);
        if (value == null || value.isBlank()) {
            log.warn("票据兑换成功但响应里没有字段 {}（维度 {}），实际字段：{}",
                    field, config.dimensionId(), fieldNames(response));
            throw new BusinessException(401,
                    "对方返回的用户信息缺少字段 " + field + "，请检查「用户标识字段」配置");
        }
        // 前导零是工号的一部分（00123 与 123 是两个不同的人），只去前后空白
        return value.trim();
    }

    /**
     * 真正发请求，并按 HTTP 状态判成败。
     *
     * <p>用 {@code getForEntity / postForEntity} 而不是 {@code getForObject}：后者非 2xx 会抛异常，
     * 而这里需要拿到对方的 {@code {code,message}} 把错误翻成人话。</p>
     */
    private JsonNode callAndParse(String url, String action) {
        return doCall(url, action, () -> restTemplate.getForEntity(URI.create(url), String.class));
    }

    /**
     * POST 表单提交（规范允许对方要求 POST 的变体）。
     *
     * <p>用表单而不是 JSON：规范给的这个口子原话是「POST（JSON body）」，
     * 但 {@code client_id/client_secret/ticket} 是标准的表单三元组，
     * 绝大多数框架两种都能收。选表单是因为万一对方框架只认其中一种，
     * 表单那侧的兼容性更宽 —— 真遇到只收 JSON 的，改这里一处即可。</p>
     */
    private JsonNode callAndParsePost(String url, Map<String, String> params, String action) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
        return doCall(url, action,
                () -> restTemplate.postForEntity(URI.create(url), entity, String.class));
    }

    private JsonNode doCall(String url, String action, Supplier<ResponseEntity<String>> call) {
        try {
            ResponseEntity<String> resp = call.get();
            JsonNode node = tryParseJson(resp.getBody());
            if (node == null) {
                log.error("{}：对方返回 HTTP {} 且响应体不是 JSON", action, resp.getStatusCode().value());
                throw new BusinessException(502,
                        action + "失败：对方返回的内容无法解析（HTTP " + resp.getStatusCode().value() + "）");
            }
            return node;
        } catch (HttpStatusCodeException e) {
            // 4xx/5xx：响应体里是 {code,message}，翻成人话再抛
            throw translateError(tryParseJson(e.getResponseBodyAsString()), e.getStatusCode().value(), action);
        } catch (ResourceAccessException e) {
            log.error("{}：无法连接对方系统 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：无法连接对方系统");
        } catch (RestClientException e) {
            log.error("{}：调用对方接口异常 url={}", action, maskUrl(url), e);
            throw new BusinessException(502, action + "失败：调用对方接口异常");
        }
    }

    /**
     * 把对方的错误码翻译成能直接给人看的一句话。
     *
     * <p>优先级：认识的码 > 对方给的 message > 原样带出码 > 只有 HTTP 状态。
     * 最后一档故意不写「未知错误」——那等于把排查线索丢干净了。</p>
     */
    private BusinessException translateError(JsonNode body, int httpStatus, String action) {
        String code = body == null ? "" : body.path("code").asText("");
        String message = body == null ? "" : body.path("message").asText("");
        String translated = ERROR_MESSAGES.get(code);
        String detail;
        if (translated != null) {
            detail = translated;
        } else if (!message.isBlank()) {
            detail = message;
        } else if (!code.isBlank()) {
            detail = "对方返回 " + code;
        } else {
            detail = "对方返回 HTTP " + httpStatus;
        }
        log.warn("票据模式{}失败 code={} message={} http={}", action, code, message, httpStatus);
        // 401：对方明确说「是谁不对」，这是登录失败；502：对方自己出问题，与我方配置无关
        return new BusinessException(httpStatus == 500 ? 502 : 401, action + "失败：" + detail);
    }

    /** 取地址；没配就报可操作的错，而不是拿 null 拼出 "null?client_id=..." */
    private String requireUrl(ProviderConfig config, String explicitUrl, String what) {
        if (explicitUrl == null || explicitUrl.isBlank()) {
            throw new BusinessException(500,
                    "维度 " + config.dimensionId() + " 未填写" + what
                            + "。票据模式没有固定路径，无法由根地址推导，请到「集成配置」里填完整地址");
        }
        return explicitUrl.trim();
    }

    private String withQuery(String url, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(url);
        char sep = url.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sep).append(e.getKey()).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return sb.toString();
    }

    private JsonNode tryParseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 日志脱敏：密钥与票据绝不能进日志（规范附录 C 第 6 条） */
    private String maskUrl(String url) {
        return url.replaceAll("client_secret=[^&]*", "client_secret=***")
                  .replaceAll("ticket=[^&]*", "ticket=***");
    }

    /** 取不到字段时把响应的字段名列出来，方便一眼看出对方用的是别的名字 */
    private String fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "（非 JSON 对象）";
        }
        StringBuilder sb = new StringBuilder();
        node.fieldNames().forEachRemaining(n -> sb.append(sb.length() == 0 ? "" : ", ").append(n));
        return sb.toString();
    }
}
