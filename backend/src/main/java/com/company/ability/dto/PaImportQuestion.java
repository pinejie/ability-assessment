package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条待确认项：代码算出来的「这件事我没法替用户决定」。
 *
 * <p><b>问题由代码算，措辞由模型写。</b>代码知道「文件里没有部门」「权重合计 98%」
 * 「这一列取值不在词表里」，但不知道怎么说人话；模型知道怎么说人话，
 * 但它不该决定「哪些算问题」—— 否则它漏判一个，用户就永远看不到那个问题。</p>
 *
 * <p>{@code slot} 是回答的落点：用户答完，值最终写进这里。没有 slot 的（如 SUSPECT）
 * 只做告知，人点「知道了」即可。</p>
 */
@Data
public class PaImportQuestion {

    /** 编号：一轮内从 1 开始，模型的回答按这个编号对回来 */
    private int no;

    /** 见下方常量 */
    private String type;

    /**
     * 回答落点。同一个 slot 只允许有一个未解决问题（重复的会合并）。
     * 例：{@code dept} / {@code post.技术人员.weight} / {@code post.技术人员.item.T1.rater}
     */
    private String slot;

    /** 归属：哪个 sheet（岗位）。空 = 整份文件级 */
    private String sheetName;

    /** 代码写的问题要点（给模型当素材，也给前端当兜底文案） */
    private String topic;

    /** 模型润色后的问句；模型不可用时回落到 {@link #topic} */
    private String question;

    /** 代码给的候选值（有候选项时前端渲染成选项，用户不用手打） */
    private List<String> candidates = new ArrayList<>();

    /** 模型建议的默认答案（前端预填，人可改） */
    private String suggestion;

    /** 上下文：这条问题是怎么来的，出问题能追 */
    private Map<String, Object> context = new LinkedHashMap<>();

    /** true = 阻断（不解决不能导入）；false = 提醒（可跳过） */
    private boolean blocking;

    // ------------------------------------------------------------------ 类型

    /** 文件里找不到必填信息（部门、岗位名） */
    public static final String MISSING = "MISSING";
    /** 同一语义有多个候选（两列都像权重、两个 sheet 都像岗位） */
    public static final String AMBIGUOUS = "AMBIGUOUS";
    /** 数据自相矛盾（权重合计不为 100%、分值不连续、区间重叠） */
    public static final String CONFLICT = "CONFLICT";
    /** 取值不在词表里（评价主体写「部门负责人」） */
    public static final String UNKNOWN_VOCAB = "UNKNOWN_VOCAB";
    /** 同部门同名岗位已存在 */
    public static final String COLLISION = "COLLISION";
    /** 能导但可能不是本意（模型自己提出的疑点） */
    public static final String SUSPECT = "SUSPECT";
    /** 模型给的坐标与原文不符（模型改写文字/指错格子） */
    public static final String MISMATCH = "MISMATCH";
}
