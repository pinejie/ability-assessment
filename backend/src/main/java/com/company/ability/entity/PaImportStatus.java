package com.company.ability.entity;

/**
 * 导入会话状态（`uf_pa_import_session.status`，见 sql/55 的表头注释）。
 *
 * <p>状态是字符串而不是枚举列：库里存 {@code VARCHAR(20)}，实体里存 String，
 * 这里只做常量收口，避免各处散落魔法字符串拼错。</p>
 */
public final class PaImportStatus {

    private PaImportStatus() {
    }

    /** 正在读 Excel（POI 阶段） */
    public static final String PARSING = "PARSING";

    /** 正在识别结构 / 计算待确认项 / 校验回答（可能含一次模型调用） */
    public static final String CHECKING = "CHECKING";

    /**
     * 等用户确认「哪些 sheet 要导入」。
     *
     * <p>客户这份文件实测 25 张 sheet，全导就是一次性建 25 个岗位。总览跑完先进这个状态，
     * 页面上把每张表的判断和规模列出来让人勾，勾定了才逐张做完整列映射。</p>
     */
    public static final String SELECTING = "SELECTING";

    /** 有待确认项，等用户回答 */
    public static final String NEEDS_CONFIRM = "NEEDS_CONFIRM";

    /** 没有疑问，等用户点「确认导入」 */
    public static final String READY = "READY";

    /** 正在写入 */
    public static final String APPLYING = "APPLYING";

    /** 写入完成 */
    public static final String DONE = "DONE";

    /** 本次会话失败，error 有原因 */
    public static final String FAILED = "FAILED";

    /** 追问超过安全阀，转手工映射 */
    public static final String MANUAL = "MANUAL";

    /** 超过 30 分钟无交互，被定时任务标记 */
    public static final String EXPIRED = "EXPIRED";

    /** 终态：不再接受任何写操作（回答/导入）。EXPIRED 也可续（会走「重新上传」） */
    public static boolean isTerminal(String status) {
        return DONE.equals(status) || FAILED.equals(status) || EXPIRED.equals(status);
    }

    /** 是否处于「后端有线程在跑」的状态 —— 前端据此显示转圈并继续轮询 */
    public static boolean isBusy(String status) {
        return PARSING.equals(status) || CHECKING.equals(status) || APPLYING.equals(status);
    }
}
