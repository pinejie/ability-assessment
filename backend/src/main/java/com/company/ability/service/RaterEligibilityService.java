package com.company.ability.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评价人可登录性校验。
 *
 * 存在意义：评价链接是「按 token 直达 + 登录人必须等于评价人」的模型
 * （{@code AssessFillService.assertCurrentUser}）。如果发起的评价人在 OA 里根本登不进来，
 * 这条链接从发出的那一刻就是死链 —— 消息照发、用户点开只看到 SSO 登录页，
 * 任务永远凑不齐人、永远算不出分，且没有任何地方报错。
 *
 * 判据必须与 {@code SsoLoginServiceImpl.loadActiveResource} 对齐：
 * <pre>
 *   SELECT ... FROM HrmResource WHERE &lt;match_field&gt; = ? AND status IN (0,1,2,3)
 * </pre>
 * 也就是说，一个「可评价的人」= <b>在岗（status 0/1/2/3）且该维度的匹配字段非空</b>。
 *
 * <p><b>2026-11-24 起匹配字段取自维度配置</b>（{@link PersonStatusPolicy}）：
 * 泛微维度是 {@code loginid}，其它系统是 {@code workcode}。种子数据当前全配
 * {@code loginid}，所以行为与改造前一致；某维度改成工号后，那个人群自动放开。</p>
 *
 * <p>两处历史口径都错过：</p>
 * <ul>
 *   <li>更早的 {@code status IN (0,1,2,3)} 只看状态不看 loginid，放过 1343 个
 *       「status=1 但没登录名」的人；</li>
 *   <li>2026-11-13 之前收紧成 {@code status = 1}，又把试用期/临时/试用延期共 238 人
 *       一并挡掉，与业务口径「只要在职就可以」不符。现已按业务口径放开状态。</li>
 * </ul>
 *
 * 统计口径见 {@code project/能力测评/16-发起侧人员校验.md}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaterEligibilityService {

    /** 与 {@link PersonStatusPolicy#ACTIVE_STATUSES} 同源：0=试用期 1=正式 2=临时 3=试用延期 */
    private static final Set<Integer> ACTIVE_STATUSES = PersonStatusPolicy.ACTIVE_STATUSES;

    private final JdbcTemplate jdbc;
    private final PersonStatusPolicy personStatusPolicy;

    /**
     * 批量校验。一次 SQL 查完，避免 N 次往返；返回顺序与入参一致。
     *
     * @param resourceIds 待校验的人员 ID（可含重复，内部去重）
     * @param roleLabels  人员 ID → 角色名（如「被测人」「直接上级」「客户评价人」），用于提示语
     */
    public List<Verdict> check(Collection<Long> resourceIds, Map<Long, String> roleLabels) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : resourceIds) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        // 判据来自当前维度的集成配置：泛微维度比 loginid，其它系统比 workcode。
        // 两列都查回来，判定时按配置挑一列看，省得为不同维度写两套 SQL。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, lastname, status, loginid, workcode FROM HrmResource WHERE id IN ("
                        + placeholders + ")",
                ids.toArray());
        String matchField = personStatusPolicy.matchField();

        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put(num(row.get("id")), row);
        }

        List<Verdict> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Map<String, Object> row = byId.get(id);
            String role = roleLabels == null ? null : roleLabels.get(id);
            result.add(judge(id, row, role, matchField));
        }
        return result;
    }

    /** 只挑出不合格的 */
    public List<Verdict> problems(Collection<Long> resourceIds, Map<Long, String> roleLabels) {
        List<Verdict> all = check(resourceIds, roleLabels);
        List<Verdict> bad = new ArrayList<>();
        for (Verdict v : all) {
            if (!v.eligible()) {
                bad.add(v);
            }
        }
        return bad;
    }

    /**
     * 单人判定。判据只有一条：status 属于在岗四态之一 且 该维度的匹配字段非空。
     * 其余分支都是为了给出**可操作的**原因，而不是笼统的「不可用」。
     *
     * @param matchField 本维度用哪个字段认人（{@code loginid} 或 {@code workcode}）
     */
    private Verdict judge(long id, Map<String, Object> row, String role, String matchField) {
        if (row == null) {
            return new Verdict(id, null, role, false, Reason.NOT_FOUND,
                    "人员不存在（HrmResource 无此 ID）");
        }
        String name = row.get("lastname") == null ? null : String.valueOf(row.get("lastname")).trim();
        int status = (int) num(row.get("status"));

        if (ACTIVE_STATUSES.contains(status)) {
            if (!personStatusPolicy.hasIdentity(row, matchField)) {
                // 提示语要说清缺的是哪一列：泛微维度缺登录名、工号维度缺工号，处理方式不同
                return new Verdict(id, name, role, false, Reason.NO_LOGINID,
                        "在岗但没有" + PersonStatusPolicy.fieldLabel(matchField)
                                + "，无法登录系统，请先在泛微中补齐");
            }
            return new Verdict(id, name, role, true, Reason.OK, null);
        }
        return switch (status) {
            case 4 -> new Verdict(id, name, role, false, Reason.DISMISSED,
                    "已解聘（status=4），无法参与测评");
            case 5 -> new Verdict(id, name, role, false, Reason.RESIGNED,
                    "已离职（status=5），无法参与测评");
            case 6 -> new Verdict(id, name, role, false, Reason.RETIRED,
                    "已退休（status=6），无法参与测评");
            case 7 -> new Verdict(id, name, role, false, Reason.INVALID,
                    "账号已失效（status=7），无法参与测评");
            default -> new Verdict(id, name, role, false, Reason.UNKNOWN_STATUS,
                    "状态未知（status=" + status + "），无法确认是否可登录，请先在泛微核对");
        };
    }

    /** 把不合格清单拼成一句可直接展示给 HR 的话 */
    public static String describe(List<Verdict> problems) {
        if (problems == null || problems.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("以下人员无法登录系统，不能参与本次测评：");
        for (int i = 0; i < problems.size(); i++) {
            Verdict v = problems.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(displayName(v)).append("：").append(v.message());
        }
        return sb.toString();
    }

    private static String displayName(Verdict v) {
        String name = v.name() == null || v.name().isBlank() ? "ID " + v.resourceId() : v.name();
        String role = v.role() == null || v.role().isBlank() ? "" : "（" + v.role() + "）";
        return name + role;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /**
     * 不可用原因。前端按 code 决定图标与是否需要跳转去处理。
     *
     * PROBATION / TEMPORARY / PROBATION_EXTENDED 三个码随 2026-11-13 放宽口径已废弃
     * （试用期、临时、试用延期现在算在岗，可以登录），保留枚举值只为兼容旧数据 ——
     * 生产上没有任何持久化记录引用它们，若后续清理可一并删除。
     */
    public enum Reason {
        OK,
        NOT_FOUND,
        NO_LOGINID,
        /** @deprecated 试用期已放行，不再产生该码 */
        @Deprecated
        PROBATION,
        /** @deprecated 临时人员已放行，不再产生该码 */
        @Deprecated
        TEMPORARY,
        /** @deprecated 试用延期已放行，不再产生该码 */
        @Deprecated
        PROBATION_EXTENDED,
        DISMISSED,
        RESIGNED,
        RETIRED,
        INVALID,
        UNKNOWN_STATUS
    }

    /**
     * 单人校验结论。
     *
     * @param resourceId HrmResource.id
     * @param name       姓名（查不到时为 null）
     * @param role       在本次任务中的角色（被测人 / 直接上级 / 客户评价人）
     * @param eligible   能否登录并参与测评
     * @param reason     不可用原因码；可用时为 OK
     * @param message    可直接展示的中文原因；可用时为 null
     */
    public record Verdict(long resourceId, String name, String role, boolean eligible,
                          Reason reason, String message) {
    }
}
