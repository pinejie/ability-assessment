package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 列映射：模型对一张表「排版」的回答。
 *
 * <p><b>为什么要有这个类，而不复用 {@link PaImportStructure}</b>：后者要求模型把每个格子的
 * 内容都抄一遍（带坐标），25 张表的输出量必然超出一次调用的额度（实测 max_tokens=16384
 * 在 25 张表时被截断，单张却能成功）。真正的分工应该是：</p>
 *
 * <ul>
 *   <li><b>模型</b>只看排版 —— 「第 A 列是能力类别，第 E 列是 1 分档」；</li>
 *   <li><b>代码</b>按这些列坐标去 Excel 里把每一行的原文读出来。</li>
 * </ul>
 *
 * <p>于是输出量从「每行 × 每格」降到「每表十几行」，25 张表一次就能给完。
 * 同时顺手修掉一个隐患：模型不再有机会改写原文，因为它压根不输出原文。</p>
 *
 * <p><b>表达不了的情况</b>：一个能力项跨多行、每行一档（竖排），或能力项在行、档位在列（横排）。
 * 这类排版本结构无法描述，模型会把 {@code layout} 标成 {@code OTHER}，
 * 调用方对这张表回退到逐格识别（单张表用老结构，输出量够）。</p>
 */
@Data
public class PaImportColumnMap {

    private List<SheetMap> sheets = new ArrayList<>();

    private List<String> notes = new ArrayList<>();

    @Data
    public static class SheetMap {
        private String sheetName;

        /** IMPORT / IGNORE */
        private String action = "IMPORT";

        /**
         * 排版类型。
         *
         * <ul>
         *   <li>{@code ROW_PER_ITEM} —— 一行一个能力项，档位横向排开（本次两份文件都是这种）</li>
         *   <li>{@code OTHER} —— 认不出来的排法，这张表回退到逐格识别</li>
         * </ul>
         */
        private String layout = "ROW_PER_ITEM";

        /** 表头所在行（1 起）；档名列的文字要从这一行读 */
        private Integer headerRow;

        /** 第一个数据行 */
        private Integer firstDataRow;

        /** 最后一个数据行（含）；认不准就留空，代码按「最后一行非空」兜底 */
        private Integer lastDataRow;

        /** 岗位名在哪个坐标（如 "A1"）；没有标题行留空 */
        private String postNameCell;

        /** 部门在哪个坐标；没有留空 */
        private String departmentCell;

        /** 各语义列 */
        private Columns columns = new Columns();

        /** 模型拿不准的地方 */
        private List<String> notes = new ArrayList<>();
    }

    @Data
    public static class Columns {
        /** 能力类别（通常是合并单元格，代码会向上沿用） */
        private String category;
        /** 能力项编号 */
        private String itemCode;
        /** 能力项名称 */
        private String itemName;
        /** 定义 */
        private String definition;
        /** 权重原文所在列 */
        private String weight;
        /** 评价主体所在列 */
        private String rater;

        /**
         * 档位列，按从低到高排列。
         *
         * <p>档名与分值从<b>表头</b>（{@code headerRow} 那行的本列）解析，
         * 例如表头写着「1分 · 入门/新手」→ 分值 1、档名「入门/新手」；
         * 解析不出来时按列表顺序兜底成 1、2、3…，并记一条待确认项。</p>
         */
        private List<LevelColumn> levels = new ArrayList<>();
    }

    @Data
    public static class LevelColumn {
        /** 列字母（如 "E"） */
        private String col;
        /** 表头原文，模型原样给出（如 "1分 · 入门/新手"）；用于代码解析档名与分值 */
        private String header;
    }
}
