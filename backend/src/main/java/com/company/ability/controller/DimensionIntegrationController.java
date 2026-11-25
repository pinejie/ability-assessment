package com.company.ability.controller;

import com.company.ability.dto.DimensionIntegrationSaveDTO;
import com.company.ability.dto.Result;
import com.company.ability.dto.SecretRevealDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.SecretCipher;
import com.company.ability.security.UserContext;
import com.company.ability.security.provider.IdentityProvider;
import com.company.ability.security.provider.IdentityProviderRegistry;
import com.company.ability.security.provider.ProviderOption;
import com.company.ability.service.AdminService;
import com.company.ability.service.DimensionIntegrationService;
import com.company.ability.service.DimensionIntegrationService.IntegrationRow;
import com.company.ability.vo.DimensionIntegrationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 维度集成配置：每个维度接哪套单点登录、消息往哪个渠道推。
 *
 * <p><b>为什么独立一个控制器而不是塞进 {@code AssessDimensionController}</b>：
 * 权限级别不同。维度本身的增删改是「管理员」，而这里存着各系统的 client_secret，
 * 一旦泄露等于对方系统的账号被人拿走 —— 只有超级管理员能看能改。</p>
 *
 * <p><b>密钥不回显</b>：读接口把已配置的密钥替换成占位符，保存时前端把占位符原样传回
 * 表示「不修改」。三种语义（占位符/空串/新值）在 {@link DimensionIntegrationSaveDTO} 里有说明。</p>
 *
 * <p><b>2026-11-25：单点登录下的每一项都是必填，且没有默认值</b>。
 * 这里做了三件配套的事：读取时不回任何协议默认值（页面上不出现「留空即…」的幻觉）、
 * 保存时按协议逐个查必填（缺哪项报哪项）、新维度的空壳不再预填协议与匹配字段。
 * 详见 {@link #validateByProviderShape}。</p>
 */
@Slf4j
@Tag(name = "维度集成配置", description = "单点登录与消息推送的逐维度配置（仅超管）")
@RestController
@RequestMapping("/api/v1/assess-dimensions")
@RequiredArgsConstructor
public class DimensionIntegrationController {

    private final DimensionIntegrationService integrationService;
    private final IdentityProviderRegistry providerRegistry;
    private final AdminService adminService;
    private final SecretCipher secretCipher;
    private final JdbcTemplate jdbc;

    @Operation(summary = "读取某维度的集成配置（仅超管，密钥以占位符返回）")
    @GetMapping("/{id}/integration")
    public Result<DimensionIntegrationVO> get(@PathVariable Long id) {
        adminService.requireSuperAdmin();
        String dimensionName = dimensionNameOf(id);
        IntegrationRow row = integrationService.find(id);
        if (row == null) {
            // 还没有配置行：回一份空壳，让页面能直接填，不用先点「新建」。
            //
            // 这里刻意<b>什么都不预填</b>（2026-11-25）：原来会预选协议 weaver、
            // 预选匹配字段 loginid、把三个地址按根地址展开。预填的坏处是页面一打开
            // 就是一份「看起来配好了」的配置，用户可以一路点保存 —— 而他从没确认过
            // 对方到底走 tickets 还是 code、登录名还是工号。现在打开就是空的，
            // 每个框都要自己填。协议形态拿默认的 weaver（只是为了让页面知道该显示哪几个框），
            // 但协议选择框本身为空。
            DimensionIntegrationVO blank = new DimensionIntegrationVO();
            blank.setDimensionId(id);
            blank.setDimensionName(dimensionName);
            blank.setConfigured(false);
            // 还没配密钥 = 这个维度不提供单点登录，所以是 false（与 toVO 同一套判据）
            blank.setSsoEnabled(false);
            blank.setPushEnabled(true);
            // 空壳仍带上某个协议的形态：页面要靠它决定「显示几个地址框」。
            // 优先泛微 —— 现有部署都接泛微，显示它的三个框比显示别家的更贴近实际。
            String shapeType = providerRegistry.supportedTypes().contains(
                    com.company.ability.security.provider.WeaverProvider.TYPE)
                    ? com.company.ability.security.provider.WeaverProvider.TYPE
                    : providerRegistry.supportedTypes().stream().findFirst().orElse(null);
            if (shapeType != null) {
                applyProviderShape(blank, shapeType);
            }
            // 推送渠道是维度级开关，与登录无关，仍给一个可用值
            blank.setPushChannel("weaver");
            return Result.success(blank);
        }
        return Result.success(toVO(row, dimensionName));
    }

    @Operation(summary = "可用选项（协议类型 / 匹配字段 / 推送渠道）")
    @GetMapping("/integration-options")
    public Result<Map<String, Object>> options() {
        adminService.requireSuperAdmin();
        // 每个协议各自的形态一次全给：用户在下拉框里换协议时页面要立刻变样，
        // 而重新读维度配置拿到的是「库里存的」协议，不是刚选的那个。
        Map<String, ProviderOption> providers = new java.util.LinkedHashMap<>();
        for (String type : providerRegistry.supportedTypes()) {
            providers.put(type, ProviderOption.of(providerRegistry.get(type)));
        }
        return Result.success(Map.of(
                "providerTypes", providerRegistry.supportedTypes(),
                "providerOptions", providers,
                "matchFields", List.of("loginid", "workcode"),
                "pushChannels", List.of("weaver", "wecom", "dingtalk", "email", "sms"),
                // 密钥功能是否可用：没配主密钥时保存密钥会失败，页面据此提前提示
                "secretCipherReady", secretCipher.isConfigured()));
    }

    @Operation(summary = "保存某维度的集成配置（仅超管）")
    @PutMapping("/{id}/integration")
    public Result<Void> save(@PathVariable Long id, @Valid @RequestBody DimensionIntegrationSaveDTO dto) {
        adminService.requireSuperAdmin();
        if (!id.equals(dto.getDimensionId())) {
            throw new BusinessException(400, "路径维度 ID 与请求体不一致");
        }
        // 协议类型必须是已注册的实现。校验在这里做而不是只靠前端下拉：
        // 前端可绕过，而写进库的错类型会在下周某个人点登录时才炸。
        Set<String> supported = providerRegistry.supportedTypes();
        if (!supported.contains(dto.getSsoProviderType().trim())) {
            throw new BusinessException(400, "不支持的单点登录类型：" + dto.getSsoProviderType()
                    + "，可选：" + supported);
        }
        // 取「库里原始密文」而不是 find() 里那份已解密的行：
        // 用户没改密钥时要把原密文原样写回，拿明文回写等于把密钥降级成明文存库。
        String clientSecret = resolveSecret(dto.getSsoClientSecret(),
                integrationService.rawSecret(id, "sso_client_secret"), "client_secret");
        String pushSecret = resolveSecret(dto.getPushSecret(),
                integrationService.rawSecret(id, "push_secret"), "push_secret");
        // 密钥填了就代表这个维度的单点登录是可用的 —— 校验也只在「填了密钥」时做：
        // 清空密钥＝这个维度不提供单点登录，这时允许地址留空（否则一个配了一半的维度
        // 会既关不掉、也清不干净）
        boolean ssoOn = trimOrNull(clientSecret) != null;
        if (ssoOn) {
            validateByProviderShape(dto);
        } else {
            // 清空密钥＝关掉这个维度的登录：最后一个可用维度不许关，否则谁也进不来
            requireRemainingLoginDimension(id);
        }

        // 回调路径不再由页面提供（2026-11-25）：它必须和对方登记的白名单逐字符一致，
        // 做成输入框等于给了一个「一改就全员登不进来」的开关（泛微报 1005）。
        // 做法是<b>忽略页面传来的值、把库里现有的写回去</b>，既不能改，也不会被清掉：
        // 若这里写 NULL，某天有人手工把路径设成过 /sso/callback/3，下一次保存就会把它
        // 悄悄改回默认路径，对方的白名单当场对不上 —— 这种「保存一下把人锁在门外」最不该发生。
        IntegrationRow existingRow = integrationService.find(id);
        String redirectPath = existingRow == null ? null : existingRow.ssoRedirectPath();
        // 用户信息地址同理不再由页面提供：泛微这套协议的路径固定，值只有一个答案，
        // 页面上留个框只会多一处能配错的地方。所以按协议形态判断 —— 本协议声明了这个字段
        // （目前没有协议声明，标准 OAuth2 那套默认形态声明了）才采纳页面值，否则原样写回
        // 库里现有的值。若这里无条件写 dto 的值，页面撤掉这个框之后每次保存都会把
        // 库里已有的地址清成 NULL。
        boolean profileFieldUsed = providerRegistry.get(dto.getSsoProviderType().trim())
                .shape().uses("profileUrl");
        String profileUrl = profileFieldUsed
                ? trimOrNull(dto.getSsoProfileUrl())
                : (existingRow == null ? null : existingRow.ssoProfileUrl());
        integrationService.save(new IntegrationRow(
                id,
                // sso_enabled 列留着（老链接、老数据还在读它），但页面已经没有这个开关：
                // 「能不能用」由 ssoClientSecret 是否为空决定。这里恒写 true，让库里的值
                // 不再和实际行为相反 —— 若某行原本是 0，用户改个地址保存一次就自动纠正过来。
                true,
                dto.getSsoProviderType().trim(),
                dto.getSsoClientId().trim(),
                clientSecret,
                // 这一批全部 trimOrNull：空串会一路传到 Provider 的判空里，
                // 而那里判的是 isBlank() —— 两套表示平时等价，一旦有人写 `== null`
                // 判断就会漏。只留 NULL 一种「没配」。
                trimOrNull(dto.getSsoBaseUrl()),
                trimOrNull(dto.getSsoAuthorizeUrl()),
                trimOrNull(dto.getSsoTokenUrl()),
                profileUrl,
                trimOrNull(dto.getSsoUserinfoField()),
                trimOrNull(dto.getSsoCallbackParam()),
                trimOrNull(dto.getSsoTokenMethod()),
                trimOrNull(dto.getSsoProfileMethod()),
                trimOrNull(dto.getSsoAccessTokenField()),
                dto.getSsoMatchField().trim(),
                redirectPath,
                Boolean.TRUE.equals(dto.getPushEnabled()),
                dto.getPushChannel().trim(),
                dto.getPushAgentId(),
                pushSecret,
                dto.getPushExtraJson()));
        return Result.success("保存成功", null);
    }

    /**
     * 查看已保存的密钥明文（超管，按需调用）。
     *
     * <p><b>为什么单独一个接口而不是让 GET 直接回明文</b>：配置页一打开就把密钥送进浏览器，
     * 这个明文会留在开发者工具、浏览器缓存、以及任何记录响应体的代理日志里 —— 而绝大多数
     * 打开配置页的操作只是改个地址或看个渠道，根本不需要密钥。做成按需取，正常浏览不产生明文。</p>
     *
     * <p>每次调用都记一条日志（谁、哪个维度、哪个字段），<b>不记值</b>。
     * 密钥被谁看过这件事本身是可审计信息，但密钥内容进日志等于把它又泄露一遍。</p>
     */
    @Operation(summary = "查看已保存的密钥明文（仅超管，每次调用留痕）")
    @PostMapping("/{id}/integration/secret")
    public Result<String> reveal(@PathVariable Long id, @Valid @RequestBody SecretRevealDTO dto) {
        adminService.requireSuperAdmin();
        String field = dto.getField();
        String value = integrationService.revealSecret(id, field);
        LoginUser user = UserContext.currentOrNull();
        log.warn("查看集成密钥 dimension={} field={} operator={} resourceId={}",
                id, field,
                user == null ? "?" : user.loginid(),
                user == null ? "?" : user.resourceId());
        return Result.success(value);
    }

    // ==================== 内部 ====================

    /**
     * 把三种密钥语义归一到「最终写库的值」。
     *
     * @param incoming 前端传来的值
     * @param current  库里现有值（可能已是密文）
     * @return 要写库的字符串：原密文 / 新密文 / null（清空）
     */
    private String resolveSecret(String incoming, String current, String fieldName) {
        // 占位符或未传 → 保持原值
        if (incoming == null || DimensionIntegrationVO.SECRET_MASK.equals(incoming)) {
            return current;
        }
        // 空串 → 明确清空
        if (incoming.isBlank()) {
            return null;
        }
        if (!secretCipher.isConfigured()) {
            throw new BusinessException(500,
                    "integration.master-key 未配置，无法加密保存 " + fieldName
                            + "。请在 backend/config/secret.yml 或环境变量 INTEGRATION_MASTER_KEY 里配置后重启。");
        }
        return secretCipher.encrypt(incoming.trim());
    }

    /**
     * 不允许「这一存把最后一个能登录的维度也关掉」。
     *
     * <p>没有开关之后，「清空 client_secret」就是关掉某个维度的单点登录。这本身是合法操作，
     * 但如果所有维度都被这样清空，任何人都进不来 —— 而能把密钥清空的人此刻正登录着，
     * 直到他退出才发现进不去了。启动期那道校验（{@code SsoConfigValidator}）管不到运行中，
     * 所以在这里挡一次：只要还有别的维度能登录，就放行。</p>
     */
    private void requireRemainingLoginDimension(Long savingDimensionId) {
        if (integrationService.hasOtherConfiguredDimension(savingDimensionId)) {
            return;
        }
        throw new BusinessException(400,
                "这是当前唯一一个配置了单点登录的维度，清空 client_secret 后所有人都无法登录。"
                        + "请先给另一个维度配置好单点登录，再来清空这里。");
    }

    /**
     * 按协议声明的形态逐个查必填，缺哪项报哪项。
     *
     * <p><b>为什么按协议查而不是在 DTO 上把每个字段都打 {@code @NotBlank}</b>：
     * 泛微要用户信息地址、要 profile 的请求方式、要 access_token 字段名；
     * 票据模式这三样<b>根本不存在</b>，一次兑换就拿到工号。统一加注解的结果是
     * 票据模式永远存不进去 —— 正确的配置过不了校验，只有编三个假值才能保存。
     * 所以「这一项本协议用不用得上」由 {@link IdentityProvider#shape()} 说，
     * 这里照着它查，协议用几个接口就查几个。</p>
     *
     * <p>报错信息把<b>所有</b>缺的项一次列全，而不是发现第一项就抛 ——
     * 配置页有十几个框，一次只告诉一个，用户要保存十几遍。</p>
     */
    private void validateByProviderShape(DimensionIntegrationSaveDTO dto) {
        IdentityProvider provider = providerRegistry.get(dto.getSsoProviderType().trim());
        var shape = provider.shape();

        List<String> missing = new ArrayList<>();
        // 三个接口地址：本协议声明用哪个就要求哪个（必填标记由协议给）
        for (var field : shape.fields()) {
            if (!field.required()) {
                continue;
            }
            if (explicitUrlOf(dto, field.key()) == null) {
                missing.add(field.label());
            }
        }
        // 根地址：只有「有固定路径」的协议才需要它（拼注销地址要用）。票据模式没有这个概念
        if (shape.derivableFromBase() && trimOrNull(dto.getSsoBaseUrl()) == null) {
            missing.add("认证中心根地址");
        }
        // 这些是每种协议都要的
        if (trimOrNull(dto.getSsoCallbackParam()) == null) {
            missing.add("回调票据参数名");
        }
        if (trimOrNull(dto.getSsoUserinfoField()) == null) {
            missing.add("用户标识字段");
        }
        if (trimOrNull(dto.getSsoTokenMethod()) == null) {
            missing.add("兑换请求方式");
        }
        // 这两项只有「兑换后还要再取一次身份」的协议才有意义
        if (shape.accessTokenStep()) {
            if (trimOrNull(dto.getSsoProfileMethod()) == null) {
                missing.add("用户信息请求方式");
            }
            if (trimOrNull(dto.getSsoAccessTokenField()) == null) {
                missing.add("access_token 字段名");
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        throw new BusinessException(400, "以下配置项必填且没有默认值，请填全后再保存："
                + String.join("、", missing));
    }

    /** 按 shape 的 key 取入参里对应的地址 */
    private static String explicitUrlOf(DimensionIntegrationSaveDTO dto, String key) {
        return switch (key) {
            case "authorizeUrl" -> trimOrNull(dto.getSsoAuthorizeUrl());
            case "tokenUrl" -> trimOrNull(dto.getSsoTokenUrl());
            case "profileUrl" -> trimOrNull(dto.getSsoProfileUrl());
            default -> null;
        };
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private DimensionIntegrationVO toVO(IntegrationRow row, String dimensionName) {
        DimensionIntegrationVO vo = new DimensionIntegrationVO();
        vo.setDimensionId(row.dimensionId());
        vo.setDimensionName(dimensionName);
        vo.setConfigured(true);
        // ssoEnabled 现在表示「这个维度的单点登录能不能用」，由配置是否齐全算出来，
        // 不再是库里的开关列 —— 库里那一列已不参与判断（保存时恒写 1）
        vo.setSsoEnabled(integrationService.ssoConfigured(row));
        vo.setSsoProviderType(row.ssoProviderType());
        vo.setSsoClientId(row.ssoClientId());
        // 不回显真实密钥：只告诉前端「配了」还是「没配」
        vo.setSsoClientSecret(maskIfPresent(row.ssoClientSecret()));
        vo.setSsoBaseUrl(row.ssoBaseUrl());
        vo.setSsoAuthorizeUrl(row.ssoAuthorizeUrl());
        vo.setSsoTokenUrl(row.ssoTokenUrl());
        vo.setSsoProfileUrl(row.ssoProfileUrl());
        // 库里空着就回空 —— 不再用协议默认值填进来。页面要让人看见「这一栏确实没配」，
        // 而不是看到一个像是用户填过的值
        vo.setSsoUserinfoField(row.ssoUserinfoField());
        vo.setSsoCallbackParam(row.ssoCallbackParam());
        vo.setSsoTokenMethod(row.ssoTokenMethod());
        vo.setSsoProfileMethod(row.ssoProfileMethod());
        vo.setSsoAccessTokenField(row.ssoAccessTokenField());
        vo.setSsoMatchField(row.ssoMatchField());
        vo.setSsoRedirectPath(row.ssoRedirectPath());
        vo.setPushEnabled(row.pushEnabled());
        vo.setPushChannel(row.pushChannel());
        vo.setPushAgentId(row.pushAgentId());
        vo.setPushSecret(maskIfPresent(row.pushSecretOrNull()));
        vo.setPushExtraJson(row.pushExtraJson());
        applyProviderShape(vo, row.ssoProviderType());
        expandUrls(vo);
        return vo;
    }

    /**
     * 把三个接口地址展开成「实际会请求的完整 URL」再回给页面。
     *
     * <p><b>为什么在服务端展开而不是让页面自己拼</b>：要显示得对，就必须用<b>真正发请求的那套逻辑</b>
     * （{@link com.company.ability.security.provider.IdentityProvider#resolveUrl}）。
     * 前端自己拼一份的话，改天协议路径变了，页面显示的和实际请求的就分家了。</p>
     *
     * <p><b>2026-11-25 起这只用于兼容老数据</b>：库里的地址现在是配置页真填出来的值，
     * 一定非空，展开等于原样返回。仍保留是因为存量行（以及手工写库的调试数据）可能
     * 只有根地址 —— 那时页面至少能看见「实际会打到哪」，照着保存一次就落成显式值了。</p>
     */
    private void expandUrls(DimensionIntegrationVO vo) {
        String type = vo.getSsoProviderType();
        if (type == null || !providerRegistry.supportedTypes().contains(type)) {
            return;
        }
        var provider = providerRegistry.get(type);
        vo.setSsoAuthorizeUrl(provider.resolveUrl(
                vo.getSsoAuthorizeUrl(), vo.getSsoBaseUrl(), provider.defaultAuthorizePath()));
        vo.setSsoTokenUrl(provider.resolveUrl(
                vo.getSsoTokenUrl(), vo.getSsoBaseUrl(), provider.defaultTokenPath()));
        vo.setSsoProfileUrl(provider.resolveUrl(
                vo.getSsoProfileUrl(), vo.getSsoBaseUrl(), provider.defaultProfilePath()));
    }

    /**
     * 把「本协议在页面上长什么样」装进 VO。
     *
     * <p><b>不再回任何默认值</b>（2026-11-25）：原来这里会把协议默认的回调参数名、
     * 请求方式、取码字段、身份字段一并回给页面做 placeholder。placeholder 的副作用是
     * 用户看着「有东西」就以为配好了、直接保存，于是库里那一栏一直是空的。
     * 现在只回形态（哪几个框）与三个固定路径（供「用根地址重新填」按钮代填）。</p>
     */
    private void applyProviderShape(DimensionIntegrationVO vo, String providerType) {
        if (providerType == null || !providerRegistry.supportedTypes().contains(providerType)) {
            return;
        }
        var provider = providerRegistry.get(providerType);
        vo.setDefaultAuthorizePath(provider.defaultAuthorizePath());
        vo.setDefaultTokenPath(provider.defaultTokenPath());
        vo.setDefaultProfilePath(provider.defaultProfilePath());
        vo.setSsoShape(provider.shape());
        // 实际会用的完整回调地址：给用户复制去对方的白名单，不用自己拼
        vo.setCallbackUrl(integrationService.callbackUrlOf(vo.getSsoRedirectPath()));
    }

    private static String maskIfPresent(String secret) {
        return secret == null || secret.isBlank() ? null : DimensionIntegrationVO.SECRET_MASK;
    }

    private String dimensionNameOf(Long dimensionId) {
        List<String> names = jdbc.queryForList(
                "SELECT dimension_name FROM uf_assess_dimension WHERE id = ?", String.class, dimensionId);
        if (names.isEmpty()) {
            throw new BusinessException(404, "维度不存在，ID: " + dimensionId);
        }
        return names.get(0);
    }
}
