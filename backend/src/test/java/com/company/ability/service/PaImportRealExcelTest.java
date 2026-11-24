package com.company.ability.service;

import com.company.ability.dto.PaExcelRaw;
import com.company.ability.dto.PaImportColumnMap;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportOverview;
import com.company.ability.dto.PaImportQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Excel 的分步识别测试（<b>会调模型网关</b>，默认跳过，加 {@code -Dpa.import.e2e=true} 开启）。
 *
 * <p>刻意做成「一步一测」：总览、单张识别、装配、待确认项分开断言。
 * 这样哪一步坏了一眼能看出来 —— 全塞进一个测试里，失败时只能看到最后一句。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PaImportRealExcelTest {

    /** 客户给的真实模板（25 张 sheet） */
    private static final String XLSX = "/home/wgd/ai-chat-app/data/uploads/6c0df199-7717-4c12-a936-c5e1233fb91d.xlsx";

    /** 2026-11-22 客户上传的优化版模板（同样 25 张 sheet，就是把它导入失败的那份） */
    private static final String XLSX_OPT = "/home/wgd/ai-chat-app/data/uploads/79f1e7f6-44be-4165-a59b-487fa701f530.xlsx";

    @Autowired
    private PaExcelReader reader;

    @Autowired
    private PaImportStructureAi structureAi;

    @Autowired
    private PaImportDraftAssembler assembler;

    @Autowired
    private PaImportQuestionBuilder questionBuilder;

    private static boolean enabled() {
        return Boolean.getBoolean("pa.import.e2e");
    }

    private PaExcelRaw read() throws Exception {
        File f = new File(XLSX);
        assertNotNull(f.exists() ? f : null, "样本文件不存在：" + XLSX);
        return reader.read(new FileInputStream(f), f.getName(), f.length());
    }

    @Test
    @DisplayName("总览：25 张 sheet 里认出哪些是岗位模型")
    void overview() throws Exception {
        if (!enabled()) {
            return;
        }
        PaExcelRaw raw = read();
        System.out.println("读表：" + raw.getSheets().size() + " 张 sheet");
        PaImportOverview ov = structureAi.overview(raw);
        assertNotNull(ov, "文件总览失败（检查网关配置）");
        List<String> importSheets = new ArrayList<>();
        for (PaImportOverview.SheetView v : ov.getSheets()) {
            System.out.println("  " + v.getSheetName() + " → " + v.getAction()
                    + " / " + v.getPostName() + "（" + v.getReason() + "）");
            if ("IMPORT".equalsIgnoreCase(v.getAction())) {
                importSheets.add(v.getSheetName());
            }
        }
        System.out.println("notes=" + ov.getNotes());
        // 这份文件全是岗位模型表，不要求全中，但至少要识别出绝大多数
        assertEquals(raw.getSheets().size(), ov.getSheets().size(), "每张 sheet 都要有判定");
    }

    @Test
    @DisplayName("单张识别：技术人员 + 信息化管理人员")
    void singleSheets() throws Exception {
        if (!enabled()) {
            return;
        }
        PaExcelRaw raw = read();
        for (String name : List.of("技术人员", "信息化管理人员")) {
            PaExcelRaw one = new PaExcelRaw();
            one.setFileName(raw.getFileName());
            raw.getSheets().stream().filter(s -> name.equals(s.getName())).forEach(one.getSheets()::add);
            assertEquals(1, one.getSheets().size(), "找不到 sheet「" + name + "」");

            var structure = structureAi.detect(one);
            assertNotNull(structure, "sheet「" + name + "」识别失败");
            var draft = assembler.assemble(one, structure);
            assertEquals(1, draft.getPosts().size(), "应识别出 1 个岗位");

            var post = draft.getPosts().get(0);
            System.out.println("岗位「" + post.getPostName() + "」 类别 " + post.getCategories().size()
                    + " 能力项 " + post.getItems().size() + " 区间 " + post.getBands().size());
            java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
            for (var item : post.getItems()) {
                sum = sum.add(item.getWeight() == null ? java.math.BigDecimal.ZERO : item.getWeight());
                System.out.println("   " + item.getItemCode() + " " + item.getItemName()
                        + " w=" + item.getWeight() + " rater=" + item.getRaterLeader() + "/" + item.getRater360()
                        + " levels=" + item.getLevels().size());
            }
            System.out.println("   权重合计 = " + sum);
            for (var r : draft.getReconciles()) {
                System.out.println("   对账 [" + r.getKind() + "] " + r.describe());
            }
            for (PaImportQuestion q : questionBuilder.build(draft, PaImportQuestionBuilder.none())) {
                System.out.println("   待确认 #" + q.getNo() + " [" + q.getType() + "] " + q.getTopic());
            }

            assertEquals(8, post.getItems().size(), "「" + name + "」应有 8 个能力项");
            assertEquals(0, sum.compareTo(new java.math.BigDecimal("100")), "「" + name + "」权重合计应为 100");
            assertEquals(5, post.getBands().size());
            for (var item : post.getItems()) {
                assertEquals(5, item.getLevels().size(), item.getItemCode() + " 应有 5 档");
            }
        }
    }

    /**
     * 25 张表一次性做列映射识别 —— 这正是原来失败的那条路。
     *
     * <p>老路径要求模型把 25 张表的每个格子都抄一遍，输出必然吃穿 max_tokens，
     * 用户看到「模型没能读懂这份文件的结构」。列映射每表只答十几行，
     * 这里断言的就是「同一份 25 张表的文件，一次调用能全部给完」。</p>
     */
    @Test
    @DisplayName("列映射：25 张表一次调用全部识别出来，不再被输出额度截断")
    void columnMapForWholeFile() throws Exception {
        if (!enabled()) {
            return;
        }
        PaExcelRaw raw = readOpt();
        System.out.println("读表：" + raw.getSheets().size() + " 张 sheet");
        PaImportColumnMap map = structureAi.detectColumns(raw);
        assertNotNull(map, "列映射识别失败：这是原来那个 bug 的现场（整份文件被截断）");
        assertEquals(raw.getSheets().size(), map.getSheets().size(),
                "每张 sheet 都要给出判定，少了就是被截断");

        long importable = map.getSheets().stream()
                .filter(s -> "IMPORT".equalsIgnoreCase(s.getAction())).count();
        System.out.println("IMPORT " + importable + " 张，notes=" + map.getNotes());
        for (PaImportColumnMap.SheetMap sm : map.getSheets()) {
            System.out.println("  「" + sm.getSheetName() + "」 " + sm.getAction()
                    + " layout=" + sm.getLayout()
                    + " 表头行=" + sm.getHeaderRow() + " 数据行=" + sm.getFirstDataRow() + "~" + sm.getLastDataRow()
                    + " 名称列=" + (sm.getColumns() == null ? null : sm.getColumns().getItemName())
                    + " 档位列=" + (sm.getColumns() == null ? 0 : sm.getColumns().getLevels().size()));
        }
        assertTrue(importable >= 20, "这份文件几乎全是岗位模型表，识别出 " + importable + " 张偏少");
    }

    @Test
    @DisplayName("列映射装配：真实文件里挑两张表，按列读出来的能力项与权重合计都对")
    void columnMapAssembly() throws Exception {
        if (!enabled()) {
            return;
        }
        PaExcelRaw raw = readOpt();
        List<String> picked = List.of("中层管理", "技术人员");
        PaExcelRaw subset = new PaExcelRaw();
        subset.setFileName(raw.getFileName());
        raw.getSheets().stream().filter(s -> picked.contains(s.getName()))
                .forEach(subset.getSheets()::add);
        assertEquals(2, subset.getSheets().size(), "样本里应有这两张表");

        PaImportColumnMap map = structureAi.detectColumns(subset);
        assertNotNull(map, "列映射识别失败");
        PaImportDraft draft = assembler.assembleColumns(subset, map);
        assertEquals(2, draft.getPosts().size(), "两张表各应产出一个岗位");

        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            System.out.println("岗位「" + post.getPostName() + "」 类别 " + post.getCategories().size()
                    + " 能力项 " + post.getItems().size() + " 区间 " + post.getBands().size());
            java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
            for (PaImportDraft.ItemDraft item : post.getItems()) {
                sum = sum.add(item.getWeight() == null ? java.math.BigDecimal.ZERO : item.getWeight());
                System.out.println("   " + item.getItemCode() + " " + item.getItemName()
                        + " 类别=" + item.getCategoryName() + " w=" + item.getWeight()
                        + " 档=" + item.getLevels().size());
                assertEquals(5, item.getLevels().size(), item.getItemName() + " 应有 5 档");
                assertEquals(5, item.getLevels().stream()
                        .map(PaImportDraft.LevelDraft::getScore).distinct().count(), "分值不能重复");
            }
            System.out.println("   权重合计 = " + sum);
            assertTrue(post.getItems().size() >= 8, "这份模板每张表至少 8 个能力项，实得 "
                    + post.getItems().size());
            assertEquals(0, sum.compareTo(new java.math.BigDecimal("100")),
                    "「" + post.getPostName() + "」权重合计应为 100%，实得 " + sum);
        }
        assertTrue(draft.getReconciles().isEmpty(), "列映射路径下模型不抄内容，不该有对账问题："
                + draft.getReconciles().size());
    }

    private PaExcelRaw readOpt() throws Exception {
        File f = new File(XLSX_OPT);
        assertNotNull(f.exists() ? f : null, "样本文件不存在：" + XLSX_OPT);
        return reader.read(new FileInputStream(f), f.getName(), f.length());
    }
}
