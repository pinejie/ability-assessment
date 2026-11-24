package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一结构：模型读懂表格后必须输出的<b>唯一</b>数据形状。
 *
 * <p><b>这里没有「行模式 / 列模式」</b>。一个能力项占一行、每档占一列，还是一个能力项占五行、
 * 每行一档，都只是源文件的排版差异 —— 模型在读表那一步就把它消化掉，吐出下面这个结构。
 * 代码从头到尾不知道自己读的是横排还是竖排，也<b>不应该</b>知道：一旦按排版分叉，
 * 每加一种排法就要改一次抽取器，迟早长成一堆互相冲突的分支。</p>
 *
 * <p><b>每个值都带坐标</b>（{@code cell}）。模型给的是「我认为 E4 是『产品与工艺知识不足…』」，
 * 代码拿到坐标后回 Excel 读 E4 的<b>原文</b>并逐字比对：一致就用原文入库，不一致就判模型读错了、
 * 生成一条待确认项让人裁决。这一步不额外调模型，纯字符串比对，一次性挡住
 * 「模型改写原文」和「模型指错列」两类错误。</p>
 *
 * <p>坐标格式：Excel 原生写法 {@code E4} / {@code A12}。</p>
 */
@Data
public class PaImportStructure {

    /** 一个 sheet = 一个岗位（也可以是「说明页」，用 {@link SheetModel#action} 排除） */
    private List<SheetModel> sheets = new ArrayList<>();

    /** 分值区间（判级用）的来源与取值 */
    private BandsModel bands = new BandsModel();

    /** 模型自己觉得需要人确认的地方，会转成 SUSPECT 类型的待确认项 */
    private List<String> notes = new ArrayList<>();

    // ------------------------------------------------------------------ sheet

    @Data
    public static class SheetModel {
        private String sheetName;

        /** IMPORT / IGNORE。IGNORE = 这一页不是岗位模型（填表说明、修订记录等） */
        private String action = "IMPORT";

        /** 岗位名（可能为 null = 文件里没写，要问人） */
        private String postName;

        /** 岗位名取自哪个坐标；为 null 表示模型是推断的（如取 sheet 名） */
        private String postNameCell;

        /**
         * 部门线索：文件里若写了部门，模型把原文和坐标放这；没写就留空。
         * 留空 → 代码生成 MISSING(dept) 待确认项去问人。
         */
        private CellValue department;

        /** 能力类别分组（按出现顺序） */
        private List<CategoryModel> categories = new ArrayList<>();

        /** 能力项 */
        private List<ItemModel> items = new ArrayList<>();

        /** 本 sheet 的分值区间来源；为空时用整份文件的默认 {@link PaImportStructure#getBands()} */
        private BandsModel bands;
    }

    @Data
    public static class CategoryModel {
        private String name;
        private String cell;
    }

    @Data
    public static class ItemModel {
        /** 能力类别名（已按 filledForward 解析过，不再是空格） */
        private CellValue category;

        /** 能力项编码，可空 */
        private CellValue itemCode;

        private CellValue itemName;

        private CellValue definition;

        /** 权重原文（如 "0.14"），真正的百分比换算在解析层做 */
        private CellValue weight;

        /** 评级主体原文（如 "直接上级" / "360 测评人" / "直接上级+360"） */
        private CellValue rater;

        /** 等级（方案里叫「档位」），按分值升序 */
        private List<LevelModel> levels = new ArrayList<>();
    }

    @Data
    public static class LevelModel {
        /** 等级名称（如「入门/新手」）；可为空 → 用分值兜底 */
        private CellValue levelName;

        /** 分值；{@code cell} 指向写分数的格子（可能和名称同一格） */
        private CellValue score;

        /** 行为描述 */
        private CellValue levelDesc;
    }

    // ------------------------------------------------------------------ 区间

    /**
     * 分值区间。
     *
     * <p>{@code mode}：</p>
     * <ul>
     *   <li>{@code FROM_LEVELS} —— 最常用：分值 1 的档 → {@code [1,2)}，末档 → {@code [N,+∞)}。
     *       这次导入的两个岗位都是这种，不需要人配。</li>
     *   <li>{@code FROM_SHEET} —— 表里另外有一块区间表，{@code entries} 有值。</li>
     *   <li>{@code MANUAL} —— 认不出来，人工在确认页填。</li>
     * </ul>
     */
    @Data
    public static class BandsModel {
        private String mode = "FROM_LEVELS";
        private List<BandModel> entries = new ArrayList<>();
    }

    @Data
    public static class BandModel {
        private CellValue bandName;
        private CellValue lowerBound;
        private CellValue upperBound;
    }

    // ------------------------------------------------------------------ 通用

    /**
     * 「值 + 坐标」。坐标为空表示模型没给位置（纯推断），这类值必须进待确认项。
     */
    @Data
    public static class CellValue {
        /** 模型读出来的值（仅用于和原文对账，<b>不入库</b>） */
        private String value;

        /** Excel 坐标（如 "E4"）；null = 模型推断，无出处 */
        private String cell;

        public static CellValue of(String value, String cell) {
            CellValue v = new CellValue();
            v.setValue(value);
            v.setCell(cell);
            return v;
        }

        public boolean hasValue() {
            return value != null && !value.isBlank();
        }

        public boolean hasCell() {
            return cell != null && !cell.isBlank();
        }
    }

    /** 便利方法：把 items 按类别分组（保序），装配时用 */
    public static Map<String, List<ItemModel>> groupByCategory(List<ItemModel> items) {
        Map<String, List<ItemModel>> out = new LinkedHashMap<>();
        for (ItemModel it : items) {
            String key = it.getCategory() == null || it.getCategory().getValue() == null
                    ? "" : it.getCategory().getValue().trim();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
        }
        return out;
    }
}
