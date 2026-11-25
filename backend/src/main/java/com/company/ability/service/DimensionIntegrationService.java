package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import com.company.ability.security.SecretCipher;
import com.company.ability.security.provider.ProviderConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度集成配置的读写：每个维度接哪套登录、消息往哪推，以及「某个人属于哪个维度」。
 *
 * <p><b>缓存策略</b>：配置变动极少但每个请求都要用（推送时每收件人都要定位维度），
 * 所以整表缓存。写入时清空缓存而不是改单条 —— 表只有几行，全量重载比增量更新简单且不会漏。</p>
 *
 * <p><b>为什么缓存整表而不是按维度缓存</b>：定位「某人属于哪个维度」需要遍历所有维度的
 * 分部授权，天然是全表操作。按维度缓存反而要多查一次。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DimensionIntegrationService {

    private static final String PENDING = "__PENDING__";

    private final JdbcTemplate jdbc;
    private final SecretCipher secretCipher;

    /** 回调地址的站点根，用于拼完整 redirect_uri */
    @Value("${assess.fill-base-url:}")
    private String siteBaseUrl;

    /** 整表缓存：dimensionId -> 配置行 */
    private volatile Map<Long, IntegrationRow> cache;

    private final Object loadLock = new Object();

    // ==================== 读取 ====================

    /**
     * 这个维度的单点登录是否可用。
     *
     * <p><b>只看配置是否齐全，不看开关</b>：原先还有一个「启用单点登录」开关，但它和真实能力
     * 并不挂钩 —— 开着没填密钥、填好了却关着，两种状态都能存进去，用户看到的与系统实际做的
     * 是两件事。现在「能不能用」直接由配置决定：client_id 与 client_secret 都在就能用，
     * 清掉密钥就等于这个维度不提供单点登录。</p>
     */
    public boolean ssoConfigured(IntegrationRow row) {
        return row != null
                && !isBlank(row.ssoClientId())
                && !isBlank(row.ssoClientSecret())
                && !PENDING.equals(row.ssoClientSecret());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 取某维度的登录配置，用于发起或回调。
     *
     * @throws BusinessException 该维度未配置单点登录、配置不完整，或密钥尚未回填
     */
    public ProviderConfig loginConfig(Long dimensionId) {
        IntegrationRow row = rowOf(dimensionId);
        if (row == null) {
            throw new BusinessException(500, "维度 " + dimensionId + " 未配置单点登录，"
                    + "请先在「维度管理 → 集成配置」里填写");
        }
        if (PENDING.equals(row.ssoClientSecret())) {
            // 占位符不是密钥，拿它去换 token 只会得到一句看不懂的「授权失败」。
            // 这通常发生在种子刚灌进来、回填还没成功的时候，指向日志才有用。
            throw new BusinessException(500, "维度 " + dimensionId + " 的 client_secret 尚未回填完成，"
                    + "请检查启动日志里维度集成种子的执行结果");
        }
        if (!ssoConfigured(row)) {
            throw new BusinessException(500, "维度 " + dimensionId + " 的单点登录未配置完整（缺少 client_id 或 "
                    + "client_secret），请先在「维度管理 → 集成配置」里补全");
        }
        return new ProviderConfig(
                row.ssoProviderType(),
                row.ssoClientId(),
                row.ssoClientSecret(),      // 已解密
                row.ssoBaseUrl(),
                row.ssoAuthorizeUrl(),
                row.ssoTokenUrl(),
                row.ssoProfileUrl(),
                row.ssoUserinfoField(),
                row.ssoCallbackParam(),
                row.ssoTokenMethod(),
                row.ssoProfileMethod(),
                row.ssoAccessTokenField(),
                redirectUriOf(row, dimensionId),
                dimensionId);
    }

    /**
     * 该维度<b>配置的</b>回调票据参数名。
     *
     * <p>返回 null 表示这一栏没配 —— 2026-11-25 起这是一个<b>异常状态</b>：
     * 保存时必填，所以库里有值才是正常。原来这里返回 null 时调用方会去问协议默认值，
     * 那层兜底已经删掉，调用方拿到 null 只能按「这个维度没配好」处理。</p>
     */
    public String callbackParamOf(Long dimensionId) {
        IntegrationRow row = rowOf(dimensionId);
        return row == null ? null : row.ssoCallbackParam();
    }
    /**
     * 该维度的匹配字段：认证中心返回的身份值去人员表哪一列比。
     *
     * <p>泛微维度是 {@code loginid}（它返回的就是登录名），其它系统是 {@code workcode}（工号）。
     * 不用猜：实测 164 人的 loginid 与工号不同，猜错就是这些人登不进来。</p>
     *
     * @return {@code loginid} 或 {@code workcode}；该维度未配置时返回 null
     */
    public String matchFieldOf(Long dimensionId) {
        IntegrationRow row = rowOf(dimensionId);
        return row == null ? null : row.ssoMatchField();
    }

    /**
     * 默认维度：没有指定维度时的兜底（泛微门户里已经挂出去的旧链接不带维度号）。
     * 取排序号最小、状态启用的那个，并且必须真的配置了登录。
     */
    public Long defaultDimensionId() {
        for (IntegrationRow row : all().values()) {
            if (ssoConfigured(row)) {
                return row.dimensionId();
            }
        }
        throw new BusinessException(500, "没有任何维度配置了单点登录，无法确定默认登录入口");
    }

    /**
     * 除了这一行，还有没有别的维度能登录。
     *
     * <p>用于保存时挡一次「把最后一个可用维度关掉」见
     * {@link com.company.ability.controller.DimensionIntegrationController}。启动期
     * {@code SsoConfigValidator} 只在启动那一刻看过，运行中改配置它管不到。</p>
     */
    public boolean hasOtherConfiguredDimension(Long excludedDimensionId) {
        for (IntegrationRow row : all().values()) {
            if (!row.dimensionId().equals(excludedDimensionId) && ssoConfigured(row)) {
                return true;
            }
        }
        return false;
    }

    /** 该维度配的推送渠道；未配置或停用时返回 null（调用方据此跳过推送） */
    public PushTarget pushTarget(Long dimensionId) {
        IntegrationRow row = rowOf(dimensionId);
        if (row == null || !row.pushEnabled()) {
            return null;
        }
        return new PushTarget(row.pushChannel(), row.pushExtraJson());
    }

    /**
     * 定位某人属于哪个维度（用于推送选渠道）。
     *
     * <p><b>重叠时取最具体</b>：维度授权是子树语义，上级维度的范围会覆盖下级分部。
     * 实测维度 4（国际贸易，分部 11）覆盖了维度 5（中达科技，分部 12）的全部 53 人。
     * 取「授权分部离本人最近」的那个 —— 专门为下级配的维度应当压过涵盖它的上级维度。
     * 规则同 DNS 最长后缀、路由最长前缀：层级重叠时越具体越优先。</p>
     *
     * <p><b>平级冲突</b>（两个互不包含的维度同时命中）当前数据中不存在，
     * 兜底取维度 ID 最小的并打 warn —— 真出现通常是配置错了，应当有人看到。</p>
     *
     * @return 维度 ID；找不到返回 null
     */
    public Long resolveDimension(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        List<Long> subs = jdbc.queryForList(
                "SELECT subcompanyid1 FROM HrmResource WHERE id = ?", Long.class, resourceId);
        Long subcompanyId = subs.isEmpty() ? null : subs.get(0);
        if (subcompanyId == null) {
            return null;
        }
        return resolveDimensionBySubcompany(subcompanyId);
    }

    /** 按分部定位维度（推送时通常已知分部，省一次查人） */
    public Long resolveDimensionBySubcompany(Long subcompanyId) {
        if (subcompanyId == null) {
            return null;
        }
        Map<Long, Long> parents = loadSubcompanyParents();
        // 分部链：本级 → 各级上级，index 即深度
        List<Long> chain = new ArrayList<>();
        Long current = subcompanyId;
        while (current != null && current != 0L && chain.size() < 32 && !chain.contains(current)) {
            chain.add(current);
            current = parents.get(current);
        }

        Map<Long, Integer> depthOfDimension = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : subcompanyAuths().entrySet()) {
            Long dimensionId = e.getKey();
            int best = Integer.MAX_VALUE;
            for (Long authSub : e.getValue()) {
                int idx = chain.indexOf(authSub);
                if (idx >= 0 && idx < best) {
                    best = idx;      // 越小越具体（0 = 本人所在分部直接授权）
                }
            }
            if (best != Integer.MAX_VALUE) {
                depthOfDimension.put(dimensionId, best);
            }
        }
        if (depthOfDimension.isEmpty()) {
            return null;
        }

        int bestDepth = Integer.MAX_VALUE;
        List<Long> winners = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : depthOfDimension.entrySet()) {
            int d = e.getValue();
            if (d < bestDepth) {
                bestDepth = d;
                winners.clear();
                winners.add(e.getKey());
            } else if (d == bestDepth) {
                winners.add(e.getKey());
            }
        }
        winners.sort(Long::compareTo);
        if (winners.size() > 1) {
            log.warn("分部 {} 同时命中多个同样具体的维度 {}，取最小的 {}。"
                            + "通常说明维度授权配重了，建议检查",
                    subcompanyId, winners, winners.get(0));
        }
        return winners.get(0);
    }

    // ==================== 缓存 ====================

    /** 清缓存（配置变更后调用） */
    public void invalidate() {
        cache = null;
    }

    private Map<Long, IntegrationRow> all() {
        Map<Long, IntegrationRow> snapshot = cache;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (loadLock) {
            if (cache == null) {
                cache = loadAll();
            }
            return cache;
        }
    }

    private IntegrationRow rowOf(Long dimensionId) {
        return dimensionId == null ? null : all().get(dimensionId);
    }

    private Map<Long, IntegrationRow> loadAll() {
        Map<Long, IntegrationRow> map = new LinkedHashMap<>();
        // 按维度排序号排，让 defaultDimensionId() 取到「排序最靠前」的那个
        jdbc.query("SELECT i.* FROM uf_assess_dimension_integration i "
                        + "JOIN uf_assess_dimension d ON d.id = i.dimension_id "
                        + "WHERE d.deleted = 0 AND d.status = 1 "
                        + "ORDER BY d.sort, d.id",
                rs -> {
                    IntegrationRow row = new IntegrationRow(
                            rs.getLong("dimension_id"),
                            rs.getBoolean("sso_enabled"),
                            rs.getString("sso_provider_type"),
                            rs.getString("sso_client_id"),
                            decryptOrPending(rs.getString("sso_client_secret")),
                            rs.getString("sso_base_url"),
                            rs.getString("sso_authorize_url"),
                            rs.getString("sso_token_url"),
                            rs.getString("sso_profile_url"),
                            rs.getString("sso_userinfo_field"),
                            rs.getString("sso_callback_param"),
                            rs.getString("sso_token_method"),
                            rs.getString("sso_profile_method"),
                            rs.getString("sso_access_token_field"),
                            rs.getString("sso_match_field"),
                            rs.getString("sso_redirect_path"),
                            rs.getBoolean("push_enabled"),
                            rs.getString("push_channel"),
                            rs.getString("push_agent_id"),
                            decryptOrPending(rs.getString("push_secret")),
                            rs.getString("push_extra_json"));
                    map.put(row.dimensionId(), row);
                });
        return map;
    }

    /**
     * 读出的密钥解密。占位符 {@code __PENDING__} 原样返回，
     * 由 {@link DimensionIntegrationSeeder} 在启动时回填后清缓存重新加载。
     * 明文（无 enc:v1: 前缀）原样返回，兼容手工填库调试。
     */
    private String decryptOrPending(String stored) {
        if (stored == null || PENDING.equals(stored)) {
            return stored;
        }
        return secretCipher.decrypt(stored);
    }

    /** 分部授权：维度 ID → 授权分部列表（仅 auth_type=1，排除项 auth_type=3 不参与） */
    private Map<Long, List<Long>> subcompanyAuths() {
        Map<Long, List<Long>> map = new HashMap<>();
        jdbc.query("SELECT dimension_id, auth_id FROM uf_assess_dimension_auth "
                        + "WHERE auth_type = 1",
                rs -> {
                    map.computeIfAbsent(rs.getLong("dimension_id"), k -> new ArrayList<>())
                            .add(rs.getLong("auth_id"));
                });
        return map;
    }

    private Map<Long, Long> loadSubcompanyParents() {
        Map<Long, Long> parents = new HashMap<>();
        jdbc.query("SELECT id, supsubcomid FROM HrmSubCompany WHERE COALESCE(canceled, 0) = 0",
                rs -> {
                    Object sup = rs.getObject("supsubcomid");
                    parents.put(rs.getLong("id"), sup == null ? null : ((Number) sup).longValue());
                });
        return parents;
    }

    /** 回调地址：配置了相对路径就用站点根拼，否则用默认 /sso/callback */
    private String redirectUriOf(IntegrationRow row, Long dimensionId) {
        return redirectUriOf(row.ssoRedirectPath(), dimensionId);
    }

    /**
     * 完整回调地址（站点根 + 相对路径）。
     *
     * <p>供配置页显示「该注册给对方的精确值」—— 原来页面上只看得到相对路径，
     * 完整地址得自己拿站点根手拼，拼错了对方就报 redirect_uri 不匹配。
     * 与真正发起登录时用的是同一份逻辑，所以页面上显示的就是实际会用的。</p>
     *
     * @param redirectPath 相对路径；空则用默认 /sso/callback
     * @return 完整地址；站点根没配时返回 null（不编造域名，页面显示为空）
     */
    public String callbackUrlOf(String redirectPath) {
        try {
            return redirectUriOf(redirectPath, null);
        } catch (BusinessException e) {
            // 站点根没配：页面显示空即可，不该让「读配置」这个动作失败
            return null;
        }
    }

    private String redirectUriOf(String redirectPath, Long dimensionId) {
        String path = redirectPath;
        if (path == null || path.isBlank()) {
            path = "/sso/callback";
        }
        if (path.startsWith("http")) {
            return path;
        }
        String base = siteBaseUrl == null ? "" : siteBaseUrl.trim();
        if (base.isEmpty()) {
            throw new BusinessException(500,
                    "assess.fill-base-url 未配置，无法拼出维度 " + dimensionId + " 的回调地址");
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    // ==================== 配置页读写 ====================

    /**
     * 读某维度的完整配置，供配置页回显。
     *
     * <p>与 {@link #loginConfig} 的区别：这个方法<b>不做可用性校验</b>。
     * 配置页要能打开一个还没配好的维度让人去补，而 {@code loginConfig} 会直接抛异常。</p>
     *
     * @return 配置；该维度还没有配置行时返回 null（页面据此显示「尚未配置」）
     */
    public IntegrationRow find(Long dimensionId) {
        return rowOf(dimensionId);
    }

    /**
     * 库里存的<b>原始</b>密钥（密文原样返回，不解密）。
     *
     * <p><b>为什么必须单独有这么个方法</b>：{@link #find} 返回的是<b>已解密</b>的行，
     * 供登录/推送去用。但「保存时用户没改密钥」这条路径要的是<b>原密文</b>——
     * 若把 {@code find()} 里的明文当原值写回去，那一列就从 {@code enc:v1:…} 变成明文，
     * 主密钥当场白配。这个 bug 真发生过（2026-11-24 实机保存后库里出现明文），
     * 所以两个入口分开，别图省事复用。</p>
     *
     * @param field sso_client_secret 或 push_secret
     * @return 原始值；没配或没这行时返回 null
     */
    public String rawSecret(Long dimensionId, String field) {
        String column = "push_secret".equals(field) ? "push_secret" : "sso_client_secret";
        // 列名来自上面这个三元判断，不是调用方透传，因此不存在注入面
        List<String> values = jdbc.queryForList(
                "SELECT " + column + " FROM uf_assess_dimension_integration WHERE dimension_id = ?",
                String.class, dimensionId);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 取某个密钥字段的明文，供超管「查看」。
     *
     * <p>放在服务层而不是控制器里就地取，是为了让「没配」「还没回填」这两种状态
     * 只有一处判断 —— 控制器只管记录谁看过。</p>
     *
     * @param field sso_client_secret 或 push_secret（白名单由入参 DTO 约束）
     * @throws com.company.ability.exception.BusinessException 维度没配、字段为空、或仍是占位符
     */
    public String revealSecret(Long dimensionId, String field) {
        IntegrationRow row = rowOf(dimensionId);
        String value = row == null ? null
                : ("push_secret".equals(field) ? row.pushSecretOrNull() : row.ssoClientSecret());
        if (value == null || value.isBlank()) {
            throw new BusinessException(404, "该维度还没有设置这个密钥");
        }
        if (PENDING.equals(value)) {
            // 种子还没回填成功，这时返回的 "__PENDING__" 不是密钥，给个能查的提示
            throw new BusinessException(500,
                    "密钥尚未回填完成，请检查启动日志里维度集成种子的执行结果");
        }
        return value;
    }

    /**
     * 保存配置（新增或覆盖）。
     *
     * <p>密钥三种语义由调用方（控制器）先归一到「最终要写的字符串」，这里只负责写。
     * 传进来的 secret 若已是 {@code enc:v1:} 前缀视为已加密，不重复加密 ——
     * 这样「保持原值」那条路径可以直接把库里的密文传回来，不必先解密再加密。</p>
     *
     * <p>写完清缓存：下一毫秒的登录请求就要用到新配置，缓存不能等。</p>
     */
    public void save(IntegrationRow row) {
        // MERGE 结尾那个分号不是排版习惯：SQL Server 要求 MERGE 语句必须以 ; 结束，
        // 否则整个语句报「MERGE 语句必须以分号(;)结尾」。JdbcTemplate 不会替我们补。
        jdbc.update("MERGE uf_assess_dimension_integration AS t "
                        + "USING (SELECT ? AS dimension_id) AS s ON t.dimension_id = s.dimension_id "
                        + "WHEN MATCHED THEN UPDATE SET "
                        + " sso_enabled = ?, sso_provider_type = ?, sso_client_id = ?, sso_client_secret = ?, "
                        + " sso_base_url = ?, sso_authorize_url = ?, sso_token_url = ?, sso_profile_url = ?, "
                        + " sso_userinfo_field = ?, sso_callback_param = ?, sso_token_method = ?, "
                        + " sso_profile_method = ?, sso_access_token_field = ?, "
                        + " sso_match_field = ?, sso_redirect_path = ?, "
                        + " push_enabled = ?, push_channel = ?, push_agent_id = ?, push_secret = ?, "
                        + " push_extra_json = ?, update_time = SYSDATETIME() "
                        + "WHEN NOT MATCHED THEN INSERT (dimension_id, sso_enabled, sso_provider_type, "
                        + " sso_client_id, sso_client_secret, sso_base_url, sso_authorize_url, sso_token_url, "
                        + " sso_profile_url, sso_userinfo_field, sso_callback_param, sso_token_method, "
                        + " sso_profile_method, sso_access_token_field, "
                        + " sso_match_field, sso_redirect_path, "
                        + " push_enabled, push_channel, push_agent_id, push_secret, push_extra_json, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSDATETIME());",
                row.dimensionId(),
                row.ssoEnabled(), row.ssoProviderType(), row.ssoClientId(), row.ssoClientSecret(),
                blankToNull(row.ssoBaseUrl()), blankToNull(row.ssoAuthorizeUrl()),
                blankToNull(row.ssoTokenUrl()), blankToNull(row.ssoProfileUrl()),
                blankToNull(row.ssoUserinfoField()), blankToNull(row.ssoCallbackParam()),
                blankToNull(row.ssoTokenMethod()), blankToNull(row.ssoProfileMethod()),
                blankToNull(row.ssoAccessTokenField()),
                row.ssoMatchField(), blankToNull(row.ssoRedirectPath()),
                row.pushEnabled(), row.pushChannel(), blankToNull(row.pushAgentId()),
                blankToNull(row.pushSecret()), blankToNull(row.pushExtraJson()),
                row.dimensionId(),
                row.ssoEnabled(), row.ssoProviderType(), row.ssoClientId(), row.ssoClientSecret(),
                blankToNull(row.ssoBaseUrl()), blankToNull(row.ssoAuthorizeUrl()),
                blankToNull(row.ssoTokenUrl()), blankToNull(row.ssoProfileUrl()),
                blankToNull(row.ssoUserinfoField()), blankToNull(row.ssoCallbackParam()),
                blankToNull(row.ssoTokenMethod()), blankToNull(row.ssoProfileMethod()),
                blankToNull(row.ssoAccessTokenField()),
                row.ssoMatchField(), blankToNull(row.ssoRedirectPath()),
                row.pushEnabled(), row.pushChannel(), blankToNull(row.pushAgentId()),
                blankToNull(row.pushSecret()), blankToNull(row.pushExtraJson()));
        invalidate();
        log.info("维度集成配置已保存 dimension={} provider={} channel={}",
                row.dimensionId(), row.ssoProviderType(), row.pushChannel());
    }

    /** 空串统一存 NULL：空串和 NULL 在「有没有配」的判断上会打架，只留一种表示 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ==================== 数据结构 ====================

    /** 一行配置（client_secret 已解密；未解密时保持占位符） */
    public record IntegrationRow(
            Long dimensionId,
            boolean ssoEnabled,
            String ssoProviderType,
            String ssoClientId,
            String ssoClientSecret,
            String ssoBaseUrl,
            String ssoAuthorizeUrl,
            String ssoTokenUrl,
            String ssoProfileUrl,
            String ssoUserinfoField,
            /** 回调参数名；null 表示没配过，取协议默认（泛微 ticket / OAuth2 code） */
            String ssoCallbackParam,
            /** 兑换请求方式 GET/POST；null 取协议默认 */
            String ssoTokenMethod,
            /** 用户信息请求方式 GET/POST；null 取协议默认 */
            String ssoProfileMethod,
            /** 兑换响应里取票据的字段名；null 取协议默认 */
            String ssoAccessTokenField,
            String ssoMatchField,
            String ssoRedirectPath,
            boolean pushEnabled,
            String pushChannel,
            String pushAgentId,
            String pushSecret,
            String pushExtraJson
    ) {
        public boolean secretPending() {
            return PENDING.equals(ssoClientSecret);
        }

        /** 推送密钥：列可为空，record 里保持 null 语义方便判「有没有配」 */
        public String pushSecretOrNull() {
            return pushSecret == null || pushSecret.isBlank() ? null : pushSecret;
        }
    }

    /** 推送目标：渠道 + 该渠道专有参数 */
    public record PushTarget(String channel, String extraJson) {
    }
}
