package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 人员「能不能进系统」的判据。
 *
 * <p><b>为什么单独抽出来</b>：这条判据原来散在三个地方，写死成「loginid 非空」——
 * {@link RaterEligibilityService}（发起时校验评价人）、{@code SsoLoginServiceImpl}
 * （登录时查人）、OA 侧 {@code PushMsgJob}（发送前查接收人）。三处一旦不一致，
 * 就会出现「发起时说能登、实际登不进」或「消息发了但没人能看到」这类静默事故。</p>
 *
 * <p><b>接入多系统后判据必须参数化</b>：泛微维度的人靠 {@code loginid} 登录，
 * 其它系统的人靠 {@code workcode}（工号）登录。同一个人可能在这套系统有账号、
 * 在那套没有，所以「有没有身份」要按<b>该维度配置的匹配字段</b>来看，
 * 不能一概而论。</p>
 *
 * <p><b>本步保持行为不变</b>：种子数据里所有维度都配 {@code loginid}，
 * 所以 1531 个没有泛微账号的人照旧被拒 —— 他们要到第二步把某个维度改成
 * {@code workcode} 之后才放开。这样改造与放开是两件独立的事，出问题好定位。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonStatusPolicy {

    /**
     * 在岗状态：0=试用期 1=正式 2=临时 3=试用延期。
     *
     * <p>4=已解聘 5=已离职 6=已退休 7=已失效 一律不算。
     * 这个集合必须与 {@code SsoLoginServiceImpl.ACTIVE_STATUSES} 的 SQL 字面量
     * （"0,1,2,3"）保持一致，改一处就要改另一处。</p>
     */
    public static final Set<Integer> ACTIVE_STATUSES = Set.of(0, 1, 2, 3);

    /** 与上面的集合同源的 SQL 片段，供原生查询直接拼 */
    public static final String ACTIVE_STATUSES_SQL = "0,1,2,3";

    /** 配置缺省时的匹配字段：泛微返回登录名，这是历史行为 */
    public static final String DEFAULT_MATCH_FIELD = "loginid";

    private final DimensionIntegrationService integrationService;

    /** 当前维度配置的匹配字段；未配置时按泛微口径（loginid） */
    public String matchField() {
        return matchFieldOf(DimensionContext.getOrDefault());
    }

    /** 指定维度的匹配字段；未配置时按泛微口径 */
    public String matchFieldOf(Long dimensionId) {
        String field = integrationService.matchFieldOf(dimensionId);
        return field == null || field.isBlank() ? DEFAULT_MATCH_FIELD : field;
    }

    /**
     * 这个人有没有可用于登录的身份值。
     *
     * @param row        人员行（必须包含 matchField 对应的列）
     * @param matchField {@code loginid} 或 {@code workcode}
     */
    public boolean hasIdentity(Map<String, Object> row, String matchField) {
        if (row == null) {
            return false;
        }
        Object value = row.get(matchField);
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    /** 是否在岗 */
    public boolean isActive(Object status) {
        return status instanceof Number n && ACTIVE_STATUSES.contains(n.intValue());
    }

    /** 该匹配字段在提示语里的中文说法（含前面的空格，供直接拼接） */
    public static String fieldLabel(String matchField) {
        return "workcode".equals(matchField) ? " 工号" : " OA 登录名";
    }
}
