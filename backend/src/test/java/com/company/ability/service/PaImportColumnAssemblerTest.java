package com.company.ability.service;

import com.company.ability.dto.PaExcelRaw;
import com.company.ability.dto.PaImportColumnMap;
import com.company.ability.dto.PaImportDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列映射装配（首选路径）的单测。
 *
 * <p>这条路径不调模型：模型只给列坐标，内容由装配器回 Excel 读。所以这些测试
 * <b>完全离线可跑</b>，也是这条路径唯一可靠的质量保证 —— 手工造表格比生成 xlsx 更快，
 * 也能精确摆出合并单元格、脚注行、缺列这些真实文件里才有的坑。</p>
 */
class PaImportColumnAssemblerTest {

    private final PaImportDraftAssembler assembler = new PaImportDraftAssembler();

    // ------------------------------------------------------------------ 造表

    /** 造一张「一行一个能力项、各档横排」的典型表 */
    private static PaExcelRaw.Sheet standardSheet() {
        PaExcelRaw.Sheet sheet = new PaExcelRaw.Sheet();
        sheet.setName("中层管理人员能力素质模型");
        sheet.setLastColumn("J");
        sheet.setLastRow(7);

        Map<Integer, Map<String, String>> rows = new LinkedHashMap<>();
        rows.put(1, row("A", "中层管理人员能力素质模型"));
        rows.put(2, row("A", "维度", "B", "编号", "C", "能力项", "D", "定义",
                "E", "1分 · 入门/新手", "F", "2分 · 胜任/熟手", "G", "3分 · 精通",
                "H", "4分 · 优秀", "I", "5分 · 开创/专家", "J", "权重"));
        rows.put(3, row("A", "管理自我", "B", "L1", "C", "知人善用", "D", "把合适的人放在合适的位置",
                "E", "只能识别明显差异", "F", "基本能配人", "G", "配人较准", "H", "配人很准",
                "I", "能带队复制", "J", "0.30"));
        // 第 4 行 A 列空：真实文件里是合并单元格，只有块首行有值
        rows.put(4, row("B", "L2", "C", "目标管理", "D", "把目标拆到人头上",
                "E", "不清楚目标", "F", "能跟目标", "G", "能拆目标", "H", "能调目标",
                "I", "能定目标体系", "J", "0.30"));
        // 脚注行：能力项列有值但不是能力项
        rows.put(5, row("C", "备注：权重合计 60% 为示例数据"));
        sheet.setRows(rows);
        sheet.setMerges(new ArrayList<>(List.of("A3:A4", "A1:J1")));
        return sheet;
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static PaExcelRaw rawOf(PaExcelRaw.Sheet... sheets) {
        PaExcelRaw raw = new PaExcelRaw();
        raw.setFileName("test.xlsx");
        for (PaExcelRaw.Sheet s : sheets) {
            raw.getSheets().add(s);
        }
        return raw;
    }

    /** 标准表的列映射（模拟模型给出的答案） */
    private static PaImportColumnMap.SheetMap standardMap(String sheetName) {
        PaImportColumnMap.Columns cols = new PaImportColumnMap.Columns();
        cols.setCategory("A");
        cols.setItemCode("B");
        cols.setItemName("C");
        cols.setDefinition("D");
        cols.setWeight("J");
        cols.setRater(null);
        for (String[] lv : new String[][]{{"E", "1分 · 入门/新手"}, {"F", "2分 · 胜任/熟手"},
                {"G", "3分 · 精通"}, {"H", "4分 · 优秀"}, {"I", "5分 · 开创/专家"}}) {
            PaImportColumnMap.LevelColumn lc = new PaImportColumnMap.LevelColumn();
            lc.setCol(lv[0]);
            lc.setHeader(lv[1]);
            cols.getLevels().add(lc);
        }

        PaImportColumnMap.SheetMap sm = new PaImportColumnMap.SheetMap();
        sm.setSheetName(sheetName);
        sm.setAction("IMPORT");
        sm.setLayout("ROW_PER_ITEM");
        sm.setHeaderRow(2);
        sm.setFirstDataRow(3);
        sm.setLastDataRow(5);
        sm.setPostNameCell("A1");
        sm.setColumns(cols);
        return sm;
    }

    private static PaImportColumnMap mapOf(PaImportColumnMap.SheetMap... sheets) {
        PaImportColumnMap map = new PaImportColumnMap();
        for (PaImportColumnMap.SheetMap s : sheets) {
            map.getSheets().add(s);
        }
        return map;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("按列映射装配：能力项、档位、档名、权重都从 Excel 原文来")
    void assemblesFromColumns() {
        PaExcelRaw.Sheet sheet = standardSheet();
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));

        assertEquals(1, draft.getPosts().size());
        PaImportDraft.PostDraft post = draft.getPosts().get(0);

        // 标题行「中层管理人员能力素质模型」→ 岗位名剥掉后缀
        assertEquals("中层管理人员", post.getPostName(), "岗位名应取标题行并剥掉「能力素质模型」后缀");

        // 第 5 行是脚注「备注：…」，不能被当成能力项
        assertEquals(2, post.getItems().size(), "「备注：」行不是能力项，必须跳过");

        PaImportDraft.ItemDraft first = post.getItems().get(0);
        assertEquals("知人善用", first.getItemName());
        assertEquals("L1", first.getItemCode());
        assertEquals("管理自我", first.getCategoryName());
        assertEquals("把合适的人放在合适的位置", first.getDefinition());
        assertEquals(3, first.getSourceRow(), "来源行号要留着，报错时说得出「第 3 行」");

        // 权重 0.30 + 0.30 = 0.6，既不像 1 也不像 100 → 不换算
        assertEquals(0, first.getWeight().compareTo(new BigDecimal("0.30")),
                "合计不是 1 也不是 100 时不换算，交校验器报错");

        assertEquals(5, first.getLevels().size());
        PaImportDraft.LevelDraft lv2 = first.getLevels().get(1);
        assertEquals(0, lv2.getScore().compareTo(new BigDecimal("2")), "分值从表头「2分 · 胜任/熟手」解析");
        assertEquals("胜任/熟手", lv2.getLevelName(), "档名要剥掉「2分 ·」前缀");
        assertEquals("基本能配人", lv2.getLevelDesc(), "描述是该行该列的正文");

        // 区间按分值去重升序，末档上界空
        assertEquals(5, post.getBands().size());
        assertEquals(0, post.getBands().get(0).getLowerBound().compareTo(BigDecimal.ONE));
        assertNull(post.getBands().get(4).getUpperBound());
        assertEquals("新手", post.getBands().get(0).getBandName(), "「入门/新手」→ 取斜杠后的「新手」");
    }

    @Test
    @DisplayName("合并单元格的类别列：块内每行都沿用块首的值")
    void carriesMergedCategoryForward() {
        PaExcelRaw.Sheet sheet = standardSheet();
        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet),
                mapOf(standardMap(sheet.getName())));

        List<PaImportDraft.ItemDraft> items = draft.getPosts().get(0).getItems();
        // A4 是空的，但 A3:A4 是合并区 —— valueAt 自动回落到 A3
        assertEquals("管理自我", items.get(1).getCategoryName(),
                "合并区里只有块首有值，第二行必须沿用块首的类别，否则会少一个类别");
        assertEquals(1, draft.getPosts().get(0).getCategories().size(), "两行同属一个类别，只该建一个");
    }

    @Test
    @DisplayName("表头解析不出分值时按列序 1、2、3 兜底")
    void fallsBackToPositionalScores() {
        PaExcelRaw.Sheet sheet = standardSheet();
        // 把表头改成没有数字的写法
        sheet.getRows().get(2).put("E", "待提升");
        sheet.getRows().get(2).put("F", "达标");
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.getColumns().getLevels().get(0).setHeader("待提升");
        sm.getColumns().getLevels().get(1).setHeader("达标");

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        List<PaImportDraft.LevelDraft> levels = draft.getPosts().get(0).getItems().get(0).getLevels();

        assertEquals(0, levels.get(0).getScore().compareTo(BigDecimal.ONE));
        assertEquals(0, levels.get(1).getScore().compareTo(new BigDecimal("2")),
                "解析不出分值就按列序给 1、2、3…，不要因此刷一堆问题");
        assertEquals("待提升", levels.get(0).getLevelName());
    }

    @Test
    @DisplayName("档位分值一律回 Excel 读表头，不采信模型抄的那份")
    void readsHeaderFromExcelNotFromModel() {
        PaExcelRaw.Sheet sheet = standardSheet();
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        // 模型把表头抄错了（漏了「2分 ·」），Excel 里是对的
        sm.getColumns().getLevels().get(1).setHeader("随便什么");

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        PaImportDraft.LevelDraft lv2 = draft.getPosts().get(0).getItems().get(0).getLevels().get(1);

        assertEquals(0, lv2.getScore().compareTo(new BigDecimal("2")),
                "分值必须来自表头原文，否则抄错一位就整档错位");
        assertEquals("胜任/熟手", lv2.getLevelName());
    }

    @Test
    @DisplayName("IGNORE 的表不产出岗位")
    void skipsIgnoredSheets() {
        PaExcelRaw.Sheet sheet = standardSheet();
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.setAction("IGNORE");

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        assertTrue(draft.getPosts().isEmpty());
    }

    @Test
    @DisplayName("表里没有标题行时用 sheet 名当岗位名")
    void fallsBackToSheetName() {
        PaExcelRaw.Sheet sheet = standardSheet();
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.setPostNameCell(null);

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        assertEquals("中层管理人员", draft.getPosts().get(0).getPostName(),
                "sheet 名是「中层管理人员能力素质模型」，剥掉后缀就是岗位名");
    }

    @Test
    @DisplayName("评价主体认不出来时记对账、不猜默认值")
    void unknownRaterIsNotGuessed() {
        PaExcelRaw.Sheet sheet = standardSheet();
        // 加一列 K 当评价主体，取值是一句看不懂的话
        sheet.setLastColumn("K");
        sheet.getRows().get(2).put("K", "评价主体");
        for (int r = 3; r <= 4; r++) {
            sheet.getRows().get(r).put("K", "按公司惯例");
        }
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.getColumns().setRater("K");

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        PaImportDraft.ItemDraft item = draft.getPosts().get(0).getItems().get(0);

        assertEquals(-1, item.getRaterLeader(), "认不出来就标 -1，不能默认成上级");
        assertEquals(-1, item.getRater360());
        assertEquals("按公司惯例", item.getRaterText(),
                "认不出来也要把原文留着，否则用户看不出到底是哪句话没认出来");
        assertFalse(draft.getReconciles().isEmpty(), "认不出来的评价主体要留一条对账，让用户看到");
    }

    @Test
    @DisplayName("「直接上级」和「直接上级+360」都能解析")
    void parsesRater() {
        PaExcelRaw.Sheet sheet = standardSheet();
        sheet.setLastColumn("K");
        sheet.getRows().get(2).put("K", "评价主体");
        sheet.getRows().get(3).put("K", "直接上级+360");
        sheet.getRows().get(4).put("K", "上级");
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.getColumns().setRater("K");

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        List<PaImportDraft.ItemDraft> items = draft.getPosts().get(0).getItems();

        assertEquals(1, items.get(0).getRaterLeader());
        assertEquals(1, items.get(0).getRater360());
        assertEquals(1, items.get(1).getRaterLeader());
        assertEquals(0, items.get(1).getRater360());
    }

    @Test
    @DisplayName("评价主体原文照抄：写法不同但开关相同的两项，原文各留各的")
    void keepsRaterTextVerbatim() {
        PaExcelRaw.Sheet sheet = standardSheet();
        sheet.setLastColumn("K");
        sheet.getRows().get(2).put("K", "评价主体");
        // 「内部客户360」和「跨部门360」都解析成 rater360=1，但业务含义不同，原文必须都留住
        sheet.getRows().get(3).put("K", "内部客户360");
        sheet.getRows().get(4).put("K", "跨部门360＋直接上级");
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.getColumns().setRater("K");

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        List<PaImportDraft.ItemDraft> items = draft.getPosts().get(0).getItems();

        assertEquals("内部客户360", items.get(0).getRaterText());
        assertEquals("跨部门360＋直接上级", items.get(1).getRaterText());
        assertEquals(1, items.get(1).getRaterLeader(), "原文里的「＋直接上级」照样要解析成开关");
        assertEquals(1, items.get(1).getRater360());
    }

    @Test
    @DisplayName("两份草稿合并：列映射的表 + 逐格识别的表都在")
    void mergesTwoDrafts() {
        PaExcelRaw.Sheet a = standardSheet();
        PaExcelRaw.Sheet b = standardSheet();
        b.setName("技术人员");
        // 标题行也要换：不然两张表的岗位名都从 A1 读出来，撞成一个
        b.getRows().get(1).put("A", "技术人员能力素质模型");

        PaImportDraft columns = assembler.assembleColumns(rawOf(a), mapOf(standardMap(a.getName())));
        PaImportDraft other = assembler.assembleColumns(rawOf(b), mapOf(standardMap(b.getName())));
        assembler.mergeInto(columns, other);

        assertEquals(2, columns.getPosts().size());
        assertEquals("中层管理人员", columns.getPosts().get(0).getPostName());
        assertEquals("技术人员", columns.getPosts().get(1).getPostName());
    }

    @Test
    @DisplayName("表头前缀剥离：「1分 · 入门/新手」只剥分值，不碰档名里的斜杠")
    void stripsScorePrefixOnly() {
        assertEquals("入门/新手", PaImportDraftAssembler.stripScorePrefix("1分 · 入门/新手"));
        assertEquals("开创/专家", PaImportDraftAssembler.stripScorePrefix("5分 · 开创/专家"));
        assertEquals("精通", PaImportDraftAssembler.stripScorePrefix("3分·精通"));
        assertEquals("优秀", PaImportDraftAssembler.stripScorePrefix("4 分 - 优秀"));
        // 没有前缀就原样返回
        assertEquals("达标", PaImportDraftAssembler.stripScorePrefix("达标"));
        // 剥完为空就退回原串，不留空档名
        assertEquals("2", PaImportDraftAssembler.stripScorePrefix("2"));
    }

    @Test
    @DisplayName("脚注行识别：合计/小计/备注/说明 都不算能力项")
    void summaryRowDetection() {
        assertTrue(PaImportDraftAssembler.isSummaryRow("合计"));
        assertTrue(PaImportDraftAssembler.isSummaryRow("备注：权重合计 100%"));
        assertTrue(PaImportDraftAssembler.isSummaryRow("注: 本表由人资部提供"));
        assertTrue(PaImportDraftAssembler.isSummaryRow("说明"));
        assertFalse(PaImportDraftAssembler.isSummaryRow("知人善用"));
        assertFalse(PaImportDraftAssembler.isSummaryRow("合计能力的运用"),
                "只有行首是「合计」这类词的才拦，正常能力项名不该被误杀");
    }

    @Test
    @DisplayName("数据行范围缺失时按整表算，不会漏行")
    void usesWholeSheetWhenRangeMissing() {
        PaExcelRaw.Sheet sheet = standardSheet();
        PaImportColumnMap.SheetMap sm = standardMap(sheet.getName());
        sm.setLastDataRow(null);

        PaImportDraft draft = assembler.assembleColumns(rawOf(sheet), mapOf(sm));
        assertEquals(2, draft.getPosts().get(0).getItems().size(),
                "没给 lastDataRow 就扫到表尾，脚注行会被 isSummaryRow 拦掉");
        assertNotNull(draft.getPosts().get(0).getPostName());
    }
}
