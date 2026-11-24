package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 个人测评报告（方案 17 §7.2）：列表行、报告头、明细行。
 *
 * <p>与旧 {@code AssessSheetVO} 的差别 —— 旧报告把「标准分 / 得分率 / 综合评定」
 * 三件套摆在最前面，那是「按满分折算百分比」时代的产物。新模型没有标准分：
 * 名次感来自分值区间命中的等级说明，明细里每项展示「自评 / 上级 / 每个 360 / 综合得分」。</p>
 *
 * <p>明细列口径（2026-11-23 调整）：360 从「一个平均分」改成<b>逐人展开</b>。
 * 平均分把「张三给 2 分、李四给 5 分」压成 3.5，看起来像两个人都打了 3.5 分；
 * 而 360 测评的全部意义就在于不同视角的差异。平均分仍保留在
 * {@code rater360Score}（合计行用它，页面上不再单独成列）。</p>
 *
 * <p>自评列<b>只展示不计分</b>（口径 #13），页面上必须标注「不参与计算」——
 * 否则业务方看到自评 5 分、总得分 3.8 会以为算错了。</p>
 */
@Data
public class PaReportVO {

    /** 列表行：一个人一年一条 */
    @Data
    public static class ListItem {
        private Long sheetId;
        private Integer year;
        private Long resourceId;
        private String resourceName;
        private String deptName;
        private String postName;
        /**
         * 所属维度名。
         *
         * <p>「我的测评」把同一年度跨维度的行合并到同一个年度下（同一年两个维度各有内容时
         * 只出现一个年度选项），合并之后页面上一眼看去全是「2026」，只能靠这一列分出
         * 哪行是中达集团、哪行是国际贸易。管理员的报告页按维度上下文取数，这一列恒为同一值。</p>
         */
        private String dimensionName;
        /** 0-未评价 1-部分提交 2-已算分 */
        private Integer status;
        private String statusName;
        /** 360 测评人人数（来自该页最近一次任务） */
        private Integer rater360Count;
        private BigDecimal totalScore;
        private String bandName;
        /**
         * 封存状态（sql/64）：0 未封存 / 1 已封存。
         *
         * <p>管理端列表用它渲染「封存状态」列；自助视角（我的测评 / 下属报告）
         * 只列出已封存的行，所以那边恒为 true。</p>
         */
        private Integer sealStatus;
        private String sealTime;
        private String sealByName;
    }

    /** 「我的测评」年度下拉的一项（跨维度去重后的一年） */
    @Data
    public static class MyYear {
        private Integer year;
        /**
         * 该年度是否还有「进行中」的年度记录。
         *
         * <p>一个人可能在不同维度下同一年度各自独立（维度按公司隔离），
         * 所以判断是「任一维度下进行中即为进行中」—— 下拉里默认选中的就是它。</p>
         */
        private Boolean active;
    }

    /** 报告头 */
    @Data
    public static class Detail {
        private Long sheetId;
        private Integer year;
        private Long resourceId;
        private String resourceName;
        private String deptName;
        private String postName;
        /** 直接上级姓名：取自 OA 组织关系快照 */
        private String leaderName;
        /**
         * 测评人姓名是否可见（维度管理员 / 系统管理员 = true）。
         *
         * <p>前端据此决定明细列头写「测评人1」还是「测评人1 · 陈亿根」。
         * 不能拿 {@link #leaderName} 是否为 null 当判据 —— 报告头部的「直接上级」
         * 对本人也要显示，所以这个字段任何时候都有值。</p>
         */
        private Boolean showRaterName;
        private Integer status;
        private String statusName;
        private Integer rater360Count;
        private Integer itemCount;
        private BigDecimal selfScore;
        private BigDecimal leaderScore;
        private BigDecimal rater360Score;
        private BigDecimal totalScore;
        private String bandName;
        /**
         * 合并评语快照（口径 #14 旧实现）：算分时把上级与各 360 的评语拼成一段。
         *
         * <p>2026-11-23 起报告<b>不再展示</b>这个字段，改成 {@link #comments} 逐人分段。
         * 保留是因为它在库里有值（{@code uf_pa_sheet.comment_text}），删字段会让
         * 「历史报告怎么读」多一次考古；前端已无引用。</p>
         */
        private String commentText;
        /**
         * 评语逐人分段（2026-11-23，口径 #14 修正）。
         *
         * <p>为什么不再用合并快照：快照只在「全员齐备」时写（{@code doRecalculate(withComment=true)}），
         * 只要有一个 360 拖着不交，已交者的评语在报告上就一个字都看不到 ——
         * 王国栋那张报告（2026-11-22）就是这样丢了王建龙的「优秀」。
         * 现在实时读 {@code uf_assess_task_rater}，谁交了显示谁。</p>
         *
         * <p>顺序：直接上级在前，360 按任务名单顺序 —— 与明细列头「测评人N」严格同序，
         * 否则评论区说「测评人2」、列头「测评人2」却是另一个人。</p>
         */
        private List<CommentEntry> comments;
        private List<Row> rows;

        /**
         * 封存状态（sql/64）：0 未封存 / 1 已封存。
         *
         * <p>已封存的报告读到的是封存那一刻的快照（{@code sealed_json}），
         * 不是库里的实时分数 —— 这样封存之后无论谁重算、重新导入，
         * 本人看到的都是同一份。管理端也读快照，页面上有横幅提示。</p>
         */
        private Integer sealStatus;
        /** 封存时间（ISO 字符串）。未封存为 null */
        private String sealTime;
        /** 封存操作人姓名。未封存为 null */
        private String sealByName;

        /**
         * 当前登录人能不能封存 / 解除封存（2026-11-24）。
         *
         * <p>与「能不能看见测评人姓名」同一口径：系统管理员或该维度的维度管理员。
         * 前端据此决定要不要画那两个按钮 —— 但真正的拦截在服务层，
         * 藏按钮不是权限控制。</p>
         */
        private Boolean canSeal;

        /**
         * 现在还差几位评价人没提交（2026-11-24 需求变更：全员评完才能封存）。
         *
         * <p>口径是「最近一次任务里的 {@code status = 0}」，<b>含自评</b>。
         * 已过期 / 已作废的评价人不算 —— 否则任务作废之后这份报告永远封不了。</p>
         */
        private Integer pendingRaterCount;

        /**
         * 现在能不能封存：已出分（{@code status = 2}）且 {@code pendingRaterCount == 0}。
         *
         * <p>前端只拿它决定按钮是否置灰，真正的拦截在 {@code seal()} 里 ——
         * 藏按钮 / 置灰按钮都不是权限控制。</p>
         */
        private Boolean sealReady;
    }

    /** 一条评语：一个评价人一段 */
    @Data
    public static class CommentEntry {
        /** LEADER / RATER360 */
        private String sourceType;
        /**
         * 展示名：直接上级 → {@code 测评人1}，360 依次 {@code 测评人2、测评人3…}
         * （2026-11-23：上级不再单列一列，统一按测评人编号，口径 A）。
         */
        private String displayName;
        /** 真实姓名：仅维度管理员/系统管理员有值，其余为 null */
        private String raterName;
        private Long resourceId;
        private String comment;
        /** 提交时间，ISO 字符串；用于让人分辨「哪条是新的」 */
        private String submitTime;
    }

    /** 明细行：一个能力项一行 */
    @Data
    public static class Row {
        private Long itemId;
        private String categoryName;
        private String itemName;
        private String definition;
        /** 权重快照（百分数） */
        private BigDecimal weight;

        /** 直接上级评级名（等级名快照）；没评过为 null */
        private String leaderLevelName;
        private BigDecimal leaderScore;

        /** 360 平均分（未评的 360 不进分母）与该页实际打分人数 */
        private BigDecimal rater360Score;
        private Integer rater360Count;

        /**
         * 360 测评人逐人评级（2026-11-23）。
         *
         * <p>顺序与页面上「测评人1、测评人2…」一一对应：取该页最近一次任务的 360 名单顺序。
         * 名单里有但没评的人也在列表里（{@code levelName} 为 null，页面显示「未评」）——
         * 少一个人会让人以为名单被改过，与「360 人数取实际名单」同一口径。
         * 有评级却不在名单里的（老任务/手工改库）追加到末尾，否则数据会悄悄消失。</p>
         *
         * <p>{@code raterName} 受权限约束：调用人不是该维度管理员（也不是系统管理员）时为 null。</p>
         */
        private List<RaterEntry> rater360s;

        /** 自评评级名：只展示，不参与任何计算 */
        private String selfLevelName;
        private BigDecimal selfScore;

        /** 进入总分的项得分 = 上级与 360 的扁平平均 */
        private BigDecimal itemScore;
        /**
         * 项得分落在这项等级区间上得到的刻度名（2026-11-24），如「精进/能手」。
         *
         * <p>口径：按该能力项<b>自己的</b>等级分值切左闭右开区间 ——
         * {@code [1,2)} 入门/新手、{@code [2,3)} 胜任/熟手 … {@code [最高分, +∞)} 最高档。
         * 与岗位级 {@code uf_pa_band} 是两套刻度，不能混用：区间表按 {@code post_id} 分，
         * 无 {@code item_id}，只解释总得分。</p>
         *
         * <p>前缀已被剥掉（库里 16 个能力项的等级名带「3分 · 」前缀，46 个不带），
         * 否则页面会写成「3.67 · 3分 · 精进/能手」。项得分没算出来时为 null。</p>
         */
        private String itemLevelName;

        /**
         * 项得分落在这页岗位的分值区间上得到的档位名（2026-11-24），如「能手」。
         *
         * <p>口径：与<b>总得分</b>用同一张 {@code uf_pa_band}、同一套 {@code [lower, upper)}
         * 切法（{@code PaBandScale}），只是判的是这一项的分值。页面上的「综合得分」
         * 显示的是<b>这个</b>字段 —— 用户要求「每个综合得分也是分数 + 点 + 描述，
         * 描述是分值区间中的等级描述」。{@link #itemLevelName} 是另一套刻度
         * （{@code uf_pa_item_level}，形如「精进/能手」），接口仍然返回，页面不再显示。</p>
         *
         * <p>没出分、岗位没配档位、分值命中不到任何区间时为 null（页面只显示分数）。</p>
         */
        private String itemBandName;
    }

    /** 单个 360 测评人对某能力项的评级（报告明细列，一人一个） */
    @Data
    public static class RaterEntry {
        private Long resourceId;
        /** 姓名；非维度管理员调用时为 null，前端显示「测评人N」 */
        private String raterName;
        /** 该人给这一项选的等级名（快照）；没评为 null */
        private String levelName;
        /** 该等级的分值；没评为 null */
        private BigDecimal score;
        /**
         * 这一项是否指派给了此人（sql/60 起 360 按项指派）。
         *
         * <p>false = 「未分配」，与 true + levelName=null 的「未评」是两件事：
         * 前者催也没用（当初就没让他评），后者该催。老任务恒为 true。</p>
         */
        private Boolean assigned;
    }
}
