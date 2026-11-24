package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 装配后的草稿：文字已从 Excel 原文取回、数值已归一化、等待校验。
 *
 * <p>与 {@link PaImportStructure} 的区别：Structure 是模型交上来的「带坐标的声称」，
 * Draft 是代码按坐标回读原文、做完对账后的<b>可信数据</b>。入库只认 Draft，绝不认 Structure。</p>
 */
@Data
public class PaImportDraft {

    /** 岗位级草稿 */
    private List<PostDraft> posts = new ArrayList<>();

    /** 装配阶段发现的问题（坐标对不上、原文被改写、合并区落空等），并入待确认项 */
    private List<Reconcile> reconciles = new ArrayList<>();

    @Data
    public static class PostDraft {
        /** 岗位名（已取原文/用户确认值） */
        private String postName;

        /** 来源 sheet，用于报错定位 */
        private String sheetName;

        /** 部门 id：来源是用户确认，文件里基本不会写 */
        private Long departmentId;

        private String departmentName;

        /** 是否模型从文件里读到了部门线索 */
        private boolean departmentFromFile;

        private List<CategoryDraft> categories = new ArrayList<>();

        private List<ItemDraft> items = new ArrayList<>();

        private List<BandDraft> bands = new ArrayList<>();
    }

    @Data
    public static class CategoryDraft {
        private String categoryName;
        /** 在岗位内的排序（按类别在文件中首次出现的顺序） */
        private int sort;
    }

    @Data
    public static class ItemDraft {
        private String categoryName;
        private String itemCode;
        private String itemName;
        private String definition;
        /** 权重百分数：14 = 14%（源文件 0.14 已在此换算） */
        private java.math.BigDecimal weight;
        private int raterLeader;
        private int rater360;
        /** 评价主体原文（Excel 那格写了什么）。认不出来时也照样留着，用户才知道错在哪 */
        private String raterText;
        private int sort;
        private List<LevelDraft> levels = new ArrayList<>();

        /** 来源能力项在 Excel 里的行号，报错时能说「第 12 行」 */
        private Integer sourceRow;
    }

    @Data
    public static class LevelDraft {
        private String levelName;
        private java.math.BigDecimal score;
        private String levelDesc;
        private int sort;
    }

    @Data
    public static class BandDraft {
        private String bandName;
        private java.math.BigDecimal lowerBound;
        private java.math.BigDecimal upperBound;
        private int sort;
    }

    /**
     * 一条对账结果：模型说自己读到什么、Excel 实际是什么。
     *
     * <p>{@code kind}：</p>
     * <ul>
     *   <li>{@code REWRITTEN} —— 坐标有效但原文与模型声称不一致（模型改写了文字/指错了格子）</li>
     *   <li>{@code NO_CELL} —— 模型只给值不给坐标（纯推断，没有出处）</li>
     *   <li>{@code BAD_CELL} —— 坐标格式非法或超出表格范围</li>
     *   <li>{@code EMPTY_CELL} —— 坐标落在空格上，但模型声称那里有值</li>
     * </ul>
     */
    @Data
    public static class Reconcile {
        private String kind;
        private String sheetName;
        private String cell;
        private String claimed;
        private String actual;
        private String field;

        public String describe() {
            return switch (kind) {
                case "REWRITTEN" -> "模型说 " + cell + " 是「" + abbr(claimed) + "」，"
                        + "但表里实际是「" + abbr(actual) + "」";
                case "NO_CELL" -> "「" + abbr(claimed) + "」没有出处坐标，无法核对";
                case "BAD_CELL" -> "坐标 " + cell + " 不合法或超出表格范围";
                case "EMPTY_CELL" -> cell + " 在表里是空的，但模型说那里有「" + abbr(claimed) + "」";
                default -> kind + " " + cell;
            };
        }

        private static String abbr(String s) {
            if (s == null) {
                return "";
            }
            String one = s.replace("\n", " ").trim();
            return one.length() <= 24 ? one : one.substring(0, 24) + "…";
        }
    }
}
