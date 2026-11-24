package com.company.ability.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话状态：前端轮询拿到的就是它。
 *
 * <p><b>轮询而不是长请求</b>（业务明确要求，与人资报表同一套做法）：模型单次调用实测 18~25 秒，
 * 一张表要调一次，5 张表就是 2 分钟。一个请求挂 2 分钟，中间过个负载均衡就断，
 * 而断了以后后端还在跑 —— 用户看到失败、库里却在写。所以每个动作都是
 * {@code POST 返回 id} + {@code GET 轮询}。</p>
 *
 * <p>所有大字段（payload/mapping）都不在这里返回：前端要的是「现在到哪一步了、有什么要问我」，
 * 把整份 Excel 的原始结构推给前端既慢又没用。</p>
 */
@Data
public class PaImportSessionVO {

    private String sessionKey;

    /** 见 {@code PaImportStatus} */
    private String status;

    /** 状态的中文名（「正在识别表格结构」），前端直接展示 */
    private String statusText;

    /** 当前阶段（更细的进度文案：「识别「技术人员」这张表」） */
    private String stage;

    /** 0-100 */
    private Integer progress;

    /** 已追问轮次 / 安全阀 */
    private Integer roundNo;
    private Integer maxRounds;

    private String fileName;
    private Long fileSize;

    /** 出错原因（FAILED / 部分 warning 时有） */
    private String error;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 会话过期时间：前端拿去算「还剩几分钟」，过期后提示重新上传 */
    private LocalDateTime expireTime;

    /** 是否忙（有后端线程在跑）：前端据此决定「继续轮询」还是「停」 */
    private boolean busy;

    // ---------------- 各阶段的产出 ----------------

    /** SELECTING 阶段：文件总览 + 模型建议勾选 */
    private PaImportOverview overview;

    /** 用户已勾选的 sheet（回显） */
    private List<String> selectedSheets = new ArrayList<>();

    /** NEEDS_CONFIRM 阶段：本轮要问的问题 */
    private List<PaImportQuestion> questions = new ArrayList<>();

    /** 对话留痕（问 → 用户原话 → 落值），页面上按时间倒序展示 */
    private List<DialogEntry> dialog = new ArrayList<>();

    /** READY / APPLYING / DONE 阶段：预览 */
    private PaImportPreview preview;

    /** 用户当前的决定（回显，前端据此初始化勾选状态） */
    private PaImportPlan plan;

    /** DONE 阶段：执行结果 */
    private Object result;

    /** 是否可以点「确认导入」 */
    private boolean canApply;

    /** 是否可以提交回答 */
    private boolean canAnswer;

    /** 一条对话记录 */
    @Data
    public static class DialogEntry {
        /** QUESTION / ANSWER / RESOLVED / NOTE */
        private String kind;
        private String at;
        /** 展示文本 */
        private String text;
        /** 回答作用的 slot（前端用来标「这条解决了第 3 问」） */
        private String slot;
        /** 附加数据（解析出的值、校验结论等） */
        private Map<String, Object> detail;
    }
}
