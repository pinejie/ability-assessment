package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 能力结构导入会话（`uf_pa_import_session`，sql/55）。
 *
 * <p><b>为什么会话要落库</b>：需求是「切到别的页面再切回来还能看到」。模型单次调用实测
 * 18~25 秒，必须异步；异步任务态和对话历史都得跨请求、跨重启活着，内存扛不住。</p>
 *
 * <p><b>大字段都用 {@code updateStrategy = ALWAYS}</b>：MyBatis-Plus 默认 NOT_NULL 策略下
 * {@code setXxx(null)} 会被静默跳过 —— 过期清理要清空 payload/dialog 这些字段，用默认策略
 * 会「看起来执行了其实没清」，数据一直躺库里。这个坑在 PaSheetServiceImpl 里已经踩过一次。</p>
 *
 * <p>对外的会话标识是 {@code sessionKey}（UUID），不是自增 id —— id 可枚举，
 * 拿别人的 id 去猜会话不该成立。查会话时同时校验 {@code resourceId}。</p>
 */
@Data
@TableName("uf_pa_import_session")
public class PaImportSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外会话号（UUID），前端持有这个 */
    private String sessionKey;

    /** 所属维度（类租户），插入时由租户拦截器自动填充 */
    private Long dimensionId;

    /** 发起人 HrmResource.id：会话里带整份文件内容，不能跨人可见 */
    private Long resourceId;

    /** 见 {@link PaImportStatus} */
    private String status;

    /** 阶段文案（中文，直接展示：「识别表格布局」「写入能力项 3/8」） */
    private String stage;

    private String fileName;

    private Long fileSize;

    /** 原始表格结构 JSON（每 sheet 每行每列文本 + 合并区域），模型只读这个 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String payload;

    /** 模型认出的统一结构映射 JSON（不含任何排版概念） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String mapping;

    /**
     * 文件总览 JSON（sql/56）：每张 sheet 是不是岗位模型、叫什么岗位。
     *
     * <p>与 mapping 分开存：总览决定「用户勾哪些 sheet」，列映射只在勾定之后才算，
     * 两者的失效时机不同 —— 换选择时总览不变、映射全变。</p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String overview;

    /**
     * 用户当前的决定 JSON（sql/56）：勾选的 sheet、已确认的回答、覆盖策略、
     * 是否重算封存报告、是否撤回进行中任务。
     *
     * <p>落库才能支持「切页面回来接着调」—— 每改一个勾选都要重算预览，
     * 这些决定本身就是状态。</p>
     *
     * <p>列名是 {@code user_plan} 而不是 {@code plan}：{@code PLAN} 是 SQL Server 保留字，
     * 建列直接报 156 语法错（实测）。写成 {@code [plan]} 也能用，但那样每处原生 SQL
     * 都得记得带方括号，漏一个就是线上报错。</p>
     *
     * <p>Java 字段名也避开了 {@code plan}：MyBatis-Plus 按字段名生成别名
     * （{@code user_plan AS plan}），别名不带方括号，一样撞保留字 —— 实测整个查询
     * 报「关键字 'plan' 附近有语法错误」。字段叫 {@code userPlan} 后别名是
     * {@code user_plan AS userPlan}，两边都不撞。</p>
     */
    @TableField(value = "user_plan", updateStrategy = FieldStrategy.ALWAYS)
    private String userPlan;

    /** 当前这一轮的待确认项清单 JSON */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pending;

    /** 对话留痕 JSON：问 → 用户原话 → 模型抽出值 → 代码校验 → 最终落值 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dialog;

    /** 校验预览 JSON（三类写入分支 + 三类测评分支的后果） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String preview;

    /** 执行结果 JSON */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String result;

    /** 覆盖前旧模型快照 JSON，供页面下载留档 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String snapshot;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String error;

    /** 已追问轮次：安全阀用（连续 10 轮还有未解决项 → MANUAL） */
    private Integer roundNo;

    /** 进度 0-100 */
    private Integer progress;

    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 过期时间 = 每次交互时的 now + 30 分钟 */
    private LocalDateTime expireTime;
}
