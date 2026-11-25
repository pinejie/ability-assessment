package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.vo.PaTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息入队（§8 消息推送方案）。
 *
 * 职责边界：**只往通用队列 uf_push_msg 插记录**，不调任何泛微接口、不需要鉴权。
 * 真正的发送由泛微侧 CronJob {@code PushMsgJob} 轮询该表完成。
 *
 * <p>这张表和那个任务都是<b>业务无关</b>的通用设施（2026-11-13 重构）。
 * 凡是「该不该发、发给谁、发什么」的判断，全部留在这个类里 —— 也就是入队侧。
 * 任务只管把表里 status=0 的行打出去，它不知道什么是测评。</p>
 *
 * <p>一个人一行：同一人即使在一份任务里有多个角色，也是分别插入多行。
 * 因为每行的链接带各自的 token，合并成一条消息就只能共用一个链接，
 * 除第一个之外全部打不开。</p>
 *
 * 为什么是写库而不是调 HTTP：
 *   泛微的 sendAndpublishMessage 是 Java 静态方法不是 HTTP 端点（§8.1 已核实），
 *   测评后端拿不到。写库后跨系统交互从「同步 HTTP」降级为「异步消息表」，
 *   任一方停机都不影响另一方主流程。
 *
 * 事务传播是 REQUIRES_NEW：通知必须独立于调用方事务（Q14 §8.6.4）。
 * 撤回已 COMMIT 后再插消息，插失败不能让撤回跟着回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessPushService {

    /**
     * 泛微消息类型 typecode：能力测评提醒（ECOLOGY_MESSAGE_TYPE，SOURCENAME=能力测评提醒）。
     *
     * <p>2026-11-14 起换为本系统专属类型 596，不再借用内置的「自定义提醒」36。
     * 借用 36 的副作用是测评消息落到「流程阅知提醒」分类（groupid=2）下，
     * 和流程阅知、合同类消息混在一起；596 挂在 groupid=18，独立分类。</p>
     *
     * <p>换类型时必须同步确认 ECOLOGY_BIZ_MOBILE_CONFIG 里有一行
     * {@code MESSAGETYPEID LIKE '%,596,%'}，否则站内消息能收到、手机端收不到。
     * 本环境已存在 {@code ID=Yp6EAWg2}，无需再补。</p>
     */
    private static final int MSG_TYPE_ASSESS = 596;

    /** 正文上限，避免被泛微 truncateMessage 截断（§8.4） */
    private static final int CONTENT_MAX = 500;

    /**
     * 入口说明：附在发起与催办消息正文末尾。
     *
     * <p>为什么要写这句：消息里的链接是<b>一人一 token</b> 的，会过期、会因撤回失效，
     * 用户也常在系统消息里翻不到历史那条。没有入口说明时，唯一的自救路径是问 HR「去哪里评」——
     * 这正是 2026-11-24 之前每天被问的问题。</p>
     *
     * <p>PC 与移动端的入口位置<b>不一样</b>（泛微 e9 的门户菜单和 APP 工作台是两个配置体系），
     * 所以两端都要写清楚，不能只说「从系统进入」。</p>
     *
     * <p>撤回通知不带这句：那条消息的落点是「你的评价已作废」，人不需要去任何地方补做什么。</p>
     */
    private static final String ENTRY_HINT =
            "也可从系统入口进入：PC 端「我的门户 → 能力测评」，移动端「工作台 → 其它应用 → 能力测评」。";

    /**
     * 消息里填写的落地地址前缀。
     *
     * <p>为什么不是一个常量：P2 期间新旧两条链路并存，两边都把评价人写进
     * {@code uf_assess_task_rater}，光看 token 分不出它属于哪条链路。
     * 前缀必须由调用方（知道自己发的是哪条链路的任务）显式给出 ——
     * 前缀给错，评价人点开就是 404 或者「链接不属于你」，而这在测试里很难兜住。</p>
     *
     * <p>P3 起旧链路 {@code /assess/fill} 已随旧表一起删除，只剩 {@link #PA}；
     * 保留 record 结构是为了让「前缀由调用方显式给出」这条约束继续成立，
     * 将来再加落地页就多一个常量，不要再退回成写死在 {@link #link} 里的字符串。</p>
     */
    public record FillPaths(String pc, String mobile) {
        /** 新链条（方案 17 §7.2） */
        public static final FillPaths PA = new FillPaths("/pa/assess-fill/", "/m/pa/assess-fill/");
    }

    private final JdbcTemplate jdbc;
    private final DimensionIntegrationService integrationService;

    @Value("${assess.fill-base-url:}")
    private String fillBaseUrl;

    /**
     * 发起邀请（方案 17 §7.2）：给每个评价人各插一条。
     *
     * <p>正文里的项数<b>按人取</b>（2026-11-23 起 360 是按能力项指派的）：
     * {@code link.itemCount} 有值就用它，没有才回退到整页项数。
     * 说整页项数会让只被指派 2 项的人以为自己的链接少了半份，
     * 进而去问 HR——这类「消息与实际不符」的问询最耗人力。</p>
     */
    public int writeInvites(long taskId, int year, int itemCount, String targetName,
                             List<PaTaskVO.RaterLink> links) {
        int written = 0;
        for (PaTaskVO.RaterLink link : links) {
            String title = inviteTitle(link.getRaterType(), targetName);
            int count = link.getItemCount() == null ? itemCount : link.getItemCount();
            String content = inviteContent(link.getRaterType(), year, count, targetName);
            written += insert(link.getRaterResourceId(), title, content, link.getAccessToken(), FillPaths.PA);
        }
        log.info("发起邀请消息 taskId={}, 目标 {} 条, 实际写入 {} 条", taskId, links.size(), written);
        return written;
    }

    /**
     * 撤回通知：只发给已提交者（§8.6.1）。
     *
     * 待评者不推 —— 他们本就未提交、无损失；重新发起时会收到新邀请。
     * 链接仍用旧 token，落地后展示撤回提示页，让评价人能自己核实（§8.6.2）。
     *
     * @param submitted 已提交的评价人（raterResourceId + accessToken）
     */
    public int writeVoids(long taskId, String targetName, int year, String reason,
                          List<Map<String, Object>> submitted) {
        int written = 0;
        for (Map<String, Object> r : submitted) {
            long raterId = num(r.get("rater_resource_id"));
            String token = (String) r.get("access_token");
            String content = voidContent(targetName, year, reason);
            written += insert(raterId, "能力测评 · 您的评价已作废", content, token, FillPaths.PA);
        }
        log.info("撤回通知 taskId={}, 已提交 {} 人, 实际写入 {} 条", taskId, submitted.size(), written);
        return written;
    }

    /**
     * 催办：只推未提交的人。轮次由调用方维护在 uf_assess_task_rater.remind_count。
     *
     * <p>年度由调用方给出，<b>不再写死 2026</b>：催办可能发生在跨年之后
     * （2026 年度的任务在 2027 年 1 月还在催），写死年份会让评价人以为催的是新一年的事，
     * 反而不敢点。</p>
     */
    public int writeReminds(long taskId, int round, int year, String targetName,
                            List<Map<String, Object>> pending) {
        int written = 0;
        for (Map<String, Object> r : pending) {
            long raterId = num(r.get("rater_resource_id"));
            String token = (String) r.get("access_token");
            String type = (String) r.get("rater_type");
            String content = year + " 年度能力测评仍在进行中，请您尽快完成对 "
                    + safe(targetName) + " 的评价。" + ENTRY_HINT;
            written += insert(raterId, inviteTitle(type, targetName), content, token, FillPaths.PA);
        }
        log.info("催办消息 taskId={}, 轮次 {}, 待评 {} 人, 实际写入 {} 条",
                taskId, round, pending.size(), written);
        return written;
    }

    /**
     * 单条插入。
     *
     * <p><b>渠道按维度配置定</b>：消息发到哪，由这次操作所属维度的集成配置决定
     * （{@code uf_assess_dimension_integration.push_channel}）。没配或停用则记为空渠道 ——
     * 那样的行不会被任何发送器取走，等于不发，符合「维度没配就别推」的语义。</p>
     *
     * <p>为什么不按收件人反查维度：一条消息属于哪次操作已经明确（维度上下文就在手边），
     * 再按人去反查一遍既多一次查询，又会在「人属于多个维度」时给出歧义答案。</p>
     *
     * REQUIRES_NEW：调用方（撤回）已提交，这里再开短事务，只持有一条 INSERT 的锁 ——
     * 跨系统的锁持有时间被压缩到最小（§8.6.4 方案 A）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int insert(long receiverId, String title, String content, String token, FillPaths paths) {
        String pcLink = link(token, paths.pc());
        String mobileLink = link(token, paths.mobile());
        String channel = resolveChannel();
        if (channel == null) {
            // 维度没配推送渠道：不入队。插进去也永远没人发，只会让「待发」数字虚高
            log.debug("维度 {} 未启用推送，跳过入队 receiverId={}", DimensionContext.getOrDefault(), receiverId);
            return 0;
        }
        return jdbc.update(
                "INSERT INTO uf_push_msg (receiver_id, msg_type, title, content, " +
                "pc_url, mobile_url, status, channel) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                receiverId, MSG_TYPE_ASSESS, title, truncate(content), pcLink, mobileLink, channel);
    }

    /**
     * 本次操作所属维度配置的推送渠道。
     *
     * @return 渠道标识；维度未配置或已停用时返回 null
     */
    private String resolveChannel() {
        DimensionIntegrationService.PushTarget target =
                integrationService.pushTarget(DimensionContext.getOrDefault());
        return target == null ? null : target.channel();
    }

    // ==================== 文案 ====================

    private static String inviteTitle(String raterType, String targetName) {
        return switch (raterType == null ? "" : raterType) {
            case "SELF" -> "能力测评 · 请完成您的自评";
            case "LEADER" -> "能力测评 · 请对 " + safe(targetName) + " 进行上级评价";
            case "RATER360" -> "能力测评 · 请对 " + safe(targetName) + " 进行 360 评价";
            default -> "能力测评 · 待办评价";
        };
    }

    private static String inviteContent(String raterType, int year, int itemCount, String targetName) {
        String action = switch (raterType == null ? "" : raterType) {
            case "SELF" -> "请完成您本人的能力测评自评";
            case "LEADER" -> "请对 " + safe(targetName) + " 进行上级评价";
            case "RATER360" -> "请对 " + safe(targetName) + " 进行 360 评价";
            default -> "请完成能力测评评价";
        };
        // 旧模型说「要素」，新模型叫「能力项」（方案 17）
        return year + " 年度能力测评已发起，共 " + itemCount + " 个能力项，" + action + "。"
                + "请点击下方链接进入。" + ENTRY_HINT;
    }

    /**
     * 撤回文案携带真实原因（Q12 §8.6.2）。原因已由入参侧约束为 5–200 字客观表述，
     * 此处原样展示，让评价人知道为何被撤回、要不要重评。
     */
    private static String voidContent(String targetName, int year, String reason) {
        return "您对 " + safe(targetName) + " 的 " + year + " 年度评价已被管理员撤回。"
                + "撤回原因：" + safe(reason) + "。"
                + "原评价内容已作废清除。人力资源部将重新发起测评，届时会再次通知您评价。";
    }

    /**
     * 绝对地址：Util_Message 只在 URL 以 GCONST.getContextPath() 开头时才剥前缀，
     * 测评域名不是 OA 上下文，前缀不匹配会原样保留（§8.4）。
     * token 只放在 link_url，绝不放进 content —— 泛微会过滤并截断正文，token 被截即失效。
     *
     * PC 与移动端给不同路径（2026-11-14）：泛微 e9 点消息时按终端把 pc_url / mobile_url
     * 分别塞给 PC 端和 APP 内置浏览器，同一份页面在手机上排版不可用。
     * 两个链接互为兜底：万一分错了，PC 路由也有 UA 检测会再 replace 到 /m/。
     */
    private String link(String token, String fillPath) {
        if (token == null) {
            return null;
        }
        String base = fillBaseUrl == null ? "" : fillBaseUrl.trim();
        if (base.isEmpty()) {
            // 未配置时不编造域名：留空由泛微侧兜底，避免生成一个打不开的链接
            log.warn("assess.fill-base-url 未配置，消息链接留空");
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + fillPath + token;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= CONTENT_MAX ? s : s.substring(0, CONTENT_MAX);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "该员工" : s;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /**
     * 撤回通知的接收人：**曾经提交过**的评价人。
     *
     * 判据必须是 submit_time 而不是 status=1 —— usp_assess_void 在 COMMIT 前就把
     * status 从 1 改成 3（作废），撤回后按 status=1 查必然为空，通知永远发不出去
     * （2026-11-13 端到端暴露：submittedRaterCount=3 但 notifiedRaterCount=0）。
     * submit_time 在 proc 里不被清空，是「提交过」的稳定证据；status 3 仅表示链接失效。
     */
    public List<Map<String, Object>> submittedRaters(long taskId) {
        return jdbc.queryForList(
                "SELECT rater_resource_id, access_token FROM uf_assess_task_rater " +
                "WHERE task_id = ? AND submit_time IS NOT NULL", taskId);
    }

    /** 便捷：把「谁还没交」的查询结果整理成催办接收列表 */
    public List<Map<String, Object>> pendingRaters(long taskId, List<Long> onlyThese) {
        if (onlyThese == null || onlyThese.isEmpty()) {
            return jdbc.queryForList(
                    "SELECT rater_resource_id, access_token, rater_type FROM uf_assess_task_rater " +
                    "WHERE task_id = ? AND status = 0", taskId);
        }
        List<Map<String, Object>> all = jdbc.queryForList(
                "SELECT rater_resource_id, access_token, rater_type FROM uf_assess_task_rater " +
                "WHERE task_id = ? AND status = 0", taskId);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> r : all) {
            if (onlyThese.contains(num(r.get("rater_resource_id")))) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    /**
     * 当前催办轮次。
     *
     * <p>2026-11-13 改从 {@code uf_assess_task_rater.remind_count} 取最大值，不再解析
     * 消息表的 biz_key —— 那张表已经通用化，不再有任何业务格式的键。</p>
     *
     * <p>必须取「最大值」而不是计数：一次催 3 个人会有 3 行 remind_count 同时为 1，
     * 按行数算会得到「已催 3 轮」。</p>
     */
    public int remindRound(long taskId) {
        Integer n = jdbc.queryForObject(
                "SELECT ISNULL(MAX(remind_count), 0) FROM uf_assess_task_rater WHERE task_id = ?",
                Integer.class, taskId);
        return n == null ? 0 : n;
    }
}
