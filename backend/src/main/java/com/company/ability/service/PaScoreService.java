package com.company.ability.service;

import com.company.ability.vo.PaSheetScoreVO;

/**
 * 测评算分（方案 17 §五）。
 *
 * <p>相对旧实现的最大变化：算分从 600 行存储过程 {@code usp_assess_recalc} 搬到 Java。
 * 旧过程要处理维度/客户置 0/外部导入/动态等级/类别权重，全是特例；
 * 新公式只是「项内平均 + 加权平均 + 区间命中」三件事，放 Java 才能单测、能断点。</p>
 *
 * <p>公式（§5.1）：</p>
 * <pre>
 * ① item_score  = AVERAGE(已评的直接上级分值, 已评的每个 360 测评人分值)   —— 自评不进这一步
 * ② total_score = Σ(item_score × weight) ÷ Σ(weight)                      —— 加权平均，不是加权求和
 * ③ band_name   = 命中 uf_pa_band 中 [lower, upper) 包含 round(total, 2) 的那一行
 * </pre>
 */
public interface PaScoreService {

    /**
     * 重新计算一个测评页的得分并回写（幂等，可重复调用）。
     *
     * <p>「自评未提交不阻断出分」（口径 #13）：本方法只关心上级与 360 的评级，
     * 自评有就展示、没有就留空。</p>
     *
     * @param sheetId {@code uf_pa_sheet.id}
     */
    PaSheetScoreVO recalculate(Long sheetId);

    /**
     * 全员提交后调用：算分 + 合并评语 + 把任务置为已完成。
     *
     * <p>与 {@link #recalculate} 的分工：那个只算分（重算按钮用），
     * 这个还要推进任务状态并落合并评语（提交触发用）。</p>
     */
    PaSheetScoreVO finish(Long taskId);

    /**
     * 单人提交后的中间态：只把页面状态推到「部分提交」，<b>不出分</b>。
     *
     * <p>方案 §5.3 明确不做「部分出分」这类中间态 —— 口径只有一个是非题，
     * 八种半成品状态比一种终态难维护得多。</p>
     */
    void markPartial(Long sheetId);
}
