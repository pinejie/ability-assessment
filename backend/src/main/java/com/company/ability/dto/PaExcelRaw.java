package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 无损原始结构（POI 读出来的第一手结果）。
 *
 * <p><b>这个类存在的理由</b>：模型只负责「看懂这张表是怎么排的」，不负责抄内容。
 * 所以先把表格原封不动读出来（含合并区域、空行、单元格原始文本），
 * 后面所有入库的文字都从这里按坐标取原文，模型说的值和原文对不上就拦下来。</p>
 *
 * <p>坐标口径：{@code column} 是 Excel 列字母（A、B、…、AA），{@code row} 是 Excel 行号（从 1 起）。
 * 用 Excel 自己的坐标系而不是数组下标，是为了让「模型说的坐标」和「人看到的位置」一致 ——
 * 出问题时用户可以直接说「E4 那格不对」，不用换算。</p>
 */
@Data
public class PaExcelRaw {

    /** 文件名，仅展示 */
    private String fileName;

    private long fileSize;

    private List<Sheet> sheets = new ArrayList<>();

    /**
     * 一个工作表。
     *
     * <p>{@code rows} 只装非空行（整行全空的跳过），key 是行号，value 是「列字母 → 原文」。
     * 空单元格不入 map（缺 key = 空），这样模型看到的是真实的疏密，不会被一堆空串误导。</p>
     */
    @Data
    public static class Sheet {
        private String name;

        /** Excel 行号 → (列字母 → 单元格原文) */
        private Map<Integer, Map<String, String>> rows = new LinkedHashMap<>();

        /**
         * 合并区域，统一写成「A4:A5」。
         *
         * <p>必须保留：合并区里只有左上角有值，其余是空。不看 merges 就会把
         * 「A 列类别只填了每块第一行」误判成数据缺失 —— 这次导入的 Excel 正是这种排法。
         * 取某格的值时用 {@link #valueAt}，它会自动落到所属合并区的左上角。</p>
         */
        private List<String> merges = new ArrayList<>();

        /** 最大列字母（如 "K"），给前端画预览表头用 */
        private String lastColumn;

        /** 最大行号 */
        private int lastRow;

        /**
         * 取某格的值，合并单元格自动回落到左上角。
         *
         * @param row  Excel 行号（从 1 起）
         * @param col  列字母（如 "E"）
         * @return 原文；空格/空串返回 null
         */
        public String valueAt(int row, String col) {
            String direct = rawAt(row, col);
            if (direct != null) {
                return direct;
            }
            for (String merge : merges) {
                int[] box = parseMerge(merge);
                if (box == null) {
                    continue;
                }
                int top = box[0], left = box[1], bottom = box[2], right = box[3];
                int colIdx = columnIndex(col);
                if (row >= top && row <= bottom && colIdx >= left && colIdx <= right) {
                    return rawAt(top, columnLetter(left));
                }
            }
            return null;
        }

        /** 只取本格原文，不看合并区 */
        public String rawAt(int row, String col) {
            Map<String, String> r = rows.get(row);
            if (r == null) {
                return null;
            }
            String v = r.get(col.toUpperCase());
            return (v == null || v.isBlank()) ? null : v;
        }

        /** 解析 "A4:A5" → {topRow, leftColIdx, bottomRow, rightColIdx}；解析不了返回 null */
        public static int[] parseMerge(String ref) {
            if (ref == null) {
                return null;
            }
            int colon = ref.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            String a = ref.substring(0, colon).trim();
            String b = ref.substring(colon + 1).trim();
            int[] pa = splitCell(a);
            int[] pb = splitCell(b);
            if (pa == null || pb == null) {
                return null;
            }
            return new int[]{Math.min(pa[0], pb[0]), Math.min(pa[1], pb[1]),
                    Math.max(pa[0], pb[0]), Math.max(pa[1], pb[1])};
        }

        /** "E4" → {4, 4}（row, colIdx 从 0 起）；解析不了返回 null */
        public static int[] splitCell(String cell) {
            if (cell == null || cell.isBlank()) {
                return null;
            }
            int i = 0;
            while (i < cell.length() && Character.isLetter(cell.charAt(i))) {
                i++;
            }
            if (i == 0 || i == cell.length()) {
                return null;
            }
            String letters = cell.substring(0, i).toUpperCase();
            try {
                return new int[]{Integer.parseInt(cell.substring(i).trim()), columnIndex(letters)};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** "A" → 0，"AA" → 26 */
        public static int columnIndex(String letters) {
            int n = 0;
            for (char c : letters.toUpperCase().toCharArray()) {
                if (c < 'A' || c > 'Z') {
                    return -1;
                }
                n = n * 26 + (c - 'A' + 1);
            }
            return n - 1;
        }

        /** 0 → "A"，26 → "AA" */
        public static String columnLetter(int index) {
            if (index < 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int n = index + 1;
            while (n > 0) {
                int rem = (n - 1) % 26;
                sb.insert(0, (char) ('A' + rem));
                n = (n - 1) / 26;
            }
            return sb.toString();
        }
    }
}
