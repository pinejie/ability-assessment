package com.engine.interfaces.sj.zhongda.job.push;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cloudstore.dev.api.bean.MessageBean;
import com.cloudstore.dev.api.bean.MessageType;
import com.cloudstore.dev.api.util.Util_Message;

import weaver.conn.RecordSet;
import weaver.interfaces.schedule.BaseCronJob;
import weaver.integration.logging.Logger;
import weaver.integration.logging.LoggerFactory;

/**
 * 通用消息推送任务。
 *
 * <p>轮询 {@code uf_push_msg}，把未发送的消息推到泛微消息中心（PC + 移动端）。
 * <b>本类不含任何业务语义</b> —— 它不知道什么是测评、合同、报销。任何系统想发提醒，
 * 往那张表插一行即可。这一点是 2026-11-13 重构的结论：初版把「任务是否已撤回 /
 * 评价人是否已提交」写进了任务里，那 100 行换一个业务就全部作废。</p>
 *
 * <p><b>职责边界</b></p>
 * <ul>
 *   <li>任务负责：取未发记录 → 验证接收人还能不能收 → 发出去 → 回写状态</li>
 *   <li>入队侧负责：这条消息<b>该不该插</b>（业务状态判断都在这里）</li>
 * </ul>
 * <p>唯一的例外是接收人状态校验，它留在任务里：查 {@code HrmResource} 是 OA 通用能力，
 * 不属于任何业务 —— 人已离职、没有登录名，消息发出去也没人能看到。</p>
 *
 * <p><b>至少发送一次</b>：取 {@code status=0} → 发送 → 直接置 1，不做「认领」中间态。
 * 进程中途被杀则该行仍为 0，下轮重发。极端情况会重复一条提醒，但<b>不会丢</b>。
 * 对提醒类消息，重复的代价远小于静默丢失。</p>
 *
 * <p><b>三个必须自己补、否则消息静默丢弃或推送走偏的字段</b>（与 U8PactPushJob 同源）：</p>
 * <ul>
 *   <li>{@code enablePC} / {@code enableEM}：无默认值，两个都为 null 时 MessageTask
 *       跳过全部处理器，但 {@code sendMessage()} 仍返回 true；</li>
 *   <li>{@code messageGroupType}：取 {@code ECOLOGY_BIZ_MOBILE_CONFIG.ID}。移动端推送前
 *       {@code MsgECToEM.insureMessageType()} 会用它去查 EM 应用 agentid，为 null 时查不到，
 *       推送链路会走偏。</li>
 * </ul>
 *
 * <p><b>不能用返回值判断发送成功。</b>{@code Util_Message.sendMessage} 在
 * {@code if (isInnerEnable())} 之外直接 {@code return true}，开关没开时消息被静默丢弃
 * 却仍返回 true。所以任务启动先自检开关，开关关闭时<b>什么都不做</b>（保持 status=0 堆积），
 * 而不是标成已发。</p>
 *
 * <p><b>调度周期不在代码里。</b>由泛微后台「定时任务」页面配置。没有待发消息时
 * 只走一次索引 seek 就返回。</p>
 */
public class PushMsgJob extends BaseCronJob {

    private static final Logger log = LoggerFactory.getLogger(PushMsgJob.class);

    private static final String PUSH_TABLE = "uf_push_msg";

    /**
     * 本任务负责的推送渠道。
     *
     * <p>队列表 2026-11-24 起按 {@code channel} 分流（见 sql/67）：本任务只发泛微，
     * 其它渠道由后端各自的发送器处理。两边各扫各的行，任一侧停机不影响另一侧。</p>
     */
    private static final String CHANNEL_WEAVER = "weaver";

    /** 单次最多处理条数，避免有积压时一次捞全表 */
    private static final int BATCH_SIZE = 200;

    /** 建模数据创建人（系统管理员）。发送人固定为系统，不入表。 */
    private static final int CREATER = 1;

    /** 正文上限：ECOLOGY_MESSAGE_INFO_READ.contexts 是 varchar(4000)，中文按双字节约 2000 字 */
    private static final int MAX_CONTENT_LENGTH = 1800;

    /** 标题上限，与建表的 NVARCHAR(200) 对齐 */
    private static final int MAX_TITLE_LENGTH = 200;

    /** 接收人在岗状态：0-试用期 1-正式 2-临时 3-试用延期 */
    private static final int[] STATUS_ACTIVE = {0, 1, 2, 3};

    private static final String TITLE_FALLBACK = "系统提醒";

    @Override
    public void execute() {
        long begin = System.currentTimeMillis();
        log.info("[PushMsgJob] 任务开始");

        try {
            // 开关自检：开关没开时消息会被静默丢弃，但返回值仍是 true（见类注释）。
            // 此时必须原样保留待发状态，什么都不做——否则消息永久丢失且无人知晓。
            if (!Util_Message.isInnerEnable()) {
                log.error("[PushMsgJob] 消息中心 InnerMessage 开关未开启，本次不发送。"
                        + "待发消息 " + countPending() + " 条保持堆积，请检查"
                        + " weaver_message_center.properties 的 InnerMessage 配置");
                return;
            }
            if (!Util_Message.isPublishEnable()) {
                log.warn("[PushMsgJob] PublishMessage 开关未开启，移动端快捷推送可能收不到；"
                        + "站内消息不受影响");
            }

            List<PushRow> rows = fetchPending();
            if (rows.isEmpty()) {
                return;
            }

            // 分组配置按 msg_type 缓存：同一批里通常只有一种类型，不必每行都查库
            Map<Integer, MsgTypeInfo> msgTypeCache = new HashMap<Integer, MsgTypeInfo>();
            int sent = 0;
            int failed = 0;
            for (PushRow row : rows) {
                String receiverProblem = checkReceiver(row.receiverId);
                if (receiverProblem != null) {
                    // 接收人不可用是「明确失败」：发出去也没人能看，直接标异常并写明原因，
                    // 不像发送异常那样等下一轮重试（重试多少次结果都一样）。
                    failed++;
                    log.info("[PushMsgJob] 接收人不可用，跳过 id=" + row.id
                            + ", receiverId=" + row.receiverId + ", 原因=" + receiverProblem);
                    markFailed(row.id, receiverProblem);
                    continue;
                }
                try {
                    MsgTypeInfo msgType = resolveMsgType(row.msgType, msgTypeCache);
                    if (msgType == null) {
                        failed++;
                        markFailed(row.id, "消息类型 " + row.msgType + " 未在泛微消息中心配置");
                        continue;
                    }
                    pushOne(row, msgType);
                    markSent(row.id);
                    sent++;
                } catch (Exception e) {
                    failed++;
                    String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    log.error("[PushMsgJob] 消息发送失败 id=" + row.id
                            + ", receiverId=" + row.receiverId + ", 原因=" + reason, e);
                    markFailed(row.id, reason);
                }
            }
            log.info("[PushMsgJob] 本次完成：成功 " + sent + " 条，失败 " + failed + " 条");
        } catch (Exception e) {
            // execute() 不得向外抛异常，否则泛微调度器会当成任务崩溃
            log.error("[PushMsgJob] 任务执行失败：" + e.getMessage(), e);
        } finally {
            log.info("[PushMsgJob] 任务结束，耗时 " + (System.currentTimeMillis() - begin) + " ms");
        }
    }

    // ------------------------------------------------------------------ 取数

    /**
     * 取一批待发消息。
     *
     * <p>只读不改状态：发完立刻回写。若在发送前先把 status 置 1 再回查（认领方案），
     * 进程被杀会留下卡在「发送中」的行，需要额外的超时回收逻辑。这里选择简单且不丢消息的做法。</p>
     *
     * <p><b>只取 {@code channel='weaver'} 的行</b>：队列表已改成多渠道共用一张，
     * 别的渠道（企微 / 钉钉等）由后端各自的发送器取走。本任务只负责泛微这一条，
     * 不加这个条件会把别人的消息发到泛微去。</p>
     */
    private List<PushRow> fetchPending() {
        RecordSet rs = new RecordSet();
        rs.executeQuery("SELECT TOP (" + BATCH_SIZE + ") id, receiver_id, msg_type,"
                + " title, content, pc_url, mobile_url"
                + " FROM " + PUSH_TABLE
                + " WHERE status = 0 AND channel = " + q(CHANNEL_WEAVER) + " ORDER BY id");

        List<PushRow> rows = new ArrayList<PushRow>();
        while (rs.next()) {
            PushRow row = new PushRow();
            row.id = rs.getInt("id");
            row.receiverId = rs.getInt("receiver_id");
            row.msgType = rs.getInt("msg_type");
            row.title = trim(rs.getString("title"));
            row.content = trim(rs.getString("content"));
            row.pcUrl = rs.getString("pc_url");
            row.mobileUrl = rs.getString("mobile_url");
            rows.add(row);
        }
        return rows;
    }

    private int countPending() {
        RecordSet rs = new RecordSet();
        rs.executeQuery("SELECT COUNT(*) AS cnt FROM " + PUSH_TABLE
                + " WHERE status = 0 AND channel = " + q(CHANNEL_WEAVER));
        if (rs.next()) {
            return rs.getInt("cnt");
        }
        return 0;
    }

    // ------------------------------------------------------------------ 接收人校验

    /**
     * 接收人还能不能收到消息。返回 null 表示可以发；否则返回失败原因。
     *
     * <p>判据与 SSO 登录一致：<b>在岗（status 0/1/2/3）且 loginid 非空</b>。
     * 没有登录名的人泛微本身也鉴权不了，消息推过去点开只会卡在登录页。</p>
     *
     * <p>这一层留在任务里而不是推到入队侧，因为查 {@code HrmResource} 是 OA 通用能力，
     * 与具体业务无关；而且入队时人还在职、真正发出时可能已经离职，只有发送前查才准。</p>
     */
    private String checkReceiver(int receiverId) {
        if (receiverId <= 0) {
            return "接收人为空或非法（receiver_id=" + receiverId + "）";
        }
        RecordSet rs = new RecordSet();
        rs.executeQuery("SELECT id, status, loginid FROM HrmResource WHERE id = " + receiverId);
        if (!rs.next()) {
            return "人员不存在（HrmResource.id=" + receiverId + "）";
        }
        int status = rs.getInt("status");
        String loginid = rs.getString("loginid");
        if (!isActive(status)) {
            return "人员状态为 " + status + "（离职/退休/失效），无法接收消息";
        }
        if (isBlank(loginid)) {
            return "人员没有 OA 登录名，无法接收消息";
        }
        return null;
    }

    private static boolean isActive(int status) {
        for (int s : STATUS_ACTIVE) {
            if (s == status) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 发送

    private void pushOne(PushRow row, MsgTypeInfo msgType) throws IOException {
        MessageType messageType = msgType.messageType;
        if (messageType == null) {
            throw new IllegalStateException("消息类型 " + row.msgType + " 实例化失败");
        }

        String title = row.title.isEmpty() ? TITLE_FALLBACK : row.title;
        MessageBean bean = Util_Message.createMessage(
                messageType,
                java.util.Collections.singleton(String.valueOf(row.receiverId)),
                truncate(title, MAX_TITLE_LENGTH),
                truncate(row.content, MAX_CONTENT_LENGTH),
                row.pcUrl,
                row.mobileUrl);

        bean.setMessageGroupType(msgType.groupId);
        bean.setMessageGroupTypeName(msgType.groupName);
        bean.setIsDefault(msgType.isDefault);
        bean.setSendMobile(msgType.sendMobile);
        bean.setImgUrl(msgType.imgUrl);
        bean.setAgentId(msgType.agentId);
        bean.setBelongId(msgType.belongId);
        bean.setModuleId(msgType.moduleId);
        bean.setIsTab(msgType.isTab);
        bean.setEnablePC("y");
        bean.setEnableEM("y");
        bean.setCreater(CREATER);

        List<MessageBean> list = new ArrayList<MessageBean>();
        list.add(bean);
        Util_Message.sendAndpublishMessage(list);
    }

    private void markSent(int id) {
        RecordSet rs = new RecordSet();
        rs.executeUpdate("UPDATE " + PUSH_TABLE
                + " SET status = 1, sent_time = SYSDATETIME(), last_error = NULL"
                + " WHERE id = " + id);
    }

    /** 失败回写。状态保持 2（异常），不自动重试也不自动放弃 —— 由人工判断后改回 0 重发。 */
    private void markFailed(int id, String reason) {
        RecordSet rs = new RecordSet();
        rs.executeUpdate("UPDATE " + PUSH_TABLE
                + " SET status = 2, last_error = " + q(truncate(reason, 450))
                + " WHERE id = " + id);
    }

    // ------------------------------------------------------------------ 消息类型

    private MsgTypeInfo resolveMsgType(int typecode, Map<Integer, MsgTypeInfo> cache) {
        if (cache.containsKey(typecode)) {
            return cache.get(typecode);
        }
        MsgTypeInfo info = queryMsgType(typecode);
        cache.put(typecode, info);
        return info;
    }

    /**
     * 动态查询消息类型与分组配置。
     *
     * <p>分组按 {@code ECOLOGY_BIZ_MOBILE_CONFIG.MESSAGETYPEID} 反查（格式 {@code ,596,}），
     * 不能拿消息类型 id 直接当分组 ID —— 自定义提醒恰好两者都是 36，
     * 但其他类型不是（能力测评提醒类型 id 是 596、分组是 {@code Yp6EAWg2}；
     * 国贸采购提醒类型 id 是 547、分组是 {@code 7cBQ30c3}）。</p>
     */
    private MsgTypeInfo queryMsgType(int typecode) {
        MessageType messageType = MessageType.newInstance(typecode);
        if (messageType == null) {
            log.error("[PushMsgJob] 未查询到消息类型（ECOLOGY_MESSAGE_TYPE.typecode=" + typecode + "）");
            return null;
        }

        RecordSet rs = new RecordSet();
        rs.executeQuery("SELECT ID AS groupid, NAME AS groupname, ISDEFAULT AS isdefault,"
                + " SENDMOBILE AS sendmobile, IMGURL AS imgurl, AGENTID AS agentid,"
                + " BELONGID AS belongid, MODULEID AS moduleid, ISTAB AS istab"
                + " FROM ECOLOGY_BIZ_MOBILE_CONFIG"
                + " WHERE MESSAGETYPEID LIKE " + q("%," + typecode + ",%"));
        if (!rs.next()) {
            log.error("[PushMsgJob] 未查询到消息分组配置（ECOLOGY_BIZ_MOBILE_CONFIG.MESSAGETYPEID 含 "
                    + typecode + "）");
            return null;
        }

        MsgTypeInfo info = new MsgTypeInfo();
        info.messageType = messageType;
        info.groupId = rs.getString("groupid");
        info.groupName = rs.getString("groupname");
        info.isDefault = rs.getString("isdefault");
        info.sendMobile = rs.getString("sendmobile");
        info.imgUrl = rs.getString("imgurl");
        info.agentId = rs.getString("agentid");
        info.belongId = rs.getString("belongid");
        info.moduleId = rs.getString("moduleid");
        info.isTab = rs.getString("istab");
        return info;
    }

    // ------------------------------------------------------------------ 工具

    private static String q(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 一条待发消息 */
    private static class PushRow {
        int id;
        int receiverId;
        int msgType;
        String title;
        String content;
        String pcUrl;
        String mobileUrl;
    }

    /** 消息类型 + 分组配置（ECOLOGY_BIZ_MOBILE_CONFIG） */
    private static class MsgTypeInfo {
        MessageType messageType;
        String groupId;
        String groupName;
        String isDefault;
        String sendMobile;
        String imgUrl;
        String agentId;
        String belongId;
        String moduleId;
        String isTab;
    }
}
