package com.company.ability.service;

import com.company.ability.dto.PaExcelRaw;
import com.company.ability.dto.PaImportColumnMap;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿装配器：把「模型声称的结构」变成「按 Excel 原文回读的可信数据」。
 *
 * <p><b>这里是整个导入唯一取文字的地方。</b>模型给坐标，代码回表里读那格的真实内容：
 * 一致就用原文，不一致就判定模型读错了并生成待确认项。所以哪怕模型把
 * 「掌握公司产品结构…」改写成近义词，也进不了库。</p>
 *
 * <p>数值归一化也在这：权重 {@code 0.14 → 14}、评级主体 {@code "直接上级" → LEADER}、
 * 分值 {@code "1分 · 入门/新手" → 1}、区间 {@code [N, N+1)}（末档上界空 = +∞）。
 * 归一化只做<b>可逆的、有明确规则</b>的换算，遇到判断不了的（比如权重合计既不像 1 也不像 100）
 * 一律不猜，生成待确认项交给人。</p>
 */
@Service
public class PaImportDraftAssembler {

    private static final Logger log = LoggerFactory.getLogger(PaImportDraftAssembler.class);

    /** 权重「像不像 1」的容差：合计落在 1±0.02 视作小数权重 */
    private static final BigDecimal SUM_ONE = BigDecimal.ONE;
    /** 权重「像不像 100」的容差 */
    private static final BigDecimal SUM_HUNDRED = new BigDecimal("100");
    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("2");

    /**
     * 装配。
     *
     * @param raw        POI 读出的原始表格
     * @param structure  模型给的结构（含坐标）
     */
    public PaImportDraft assemble(PaExcelRaw raw, PaImportStructure structure) {
        PaImportDraft draft = new PaImportDraft();
        Map<String, PaExcelRaw.Sheet> byName = new LinkedHashMap<>();
        for (PaExcelRaw.Sheet s : raw.getSheets()) {
            byName.put(s.getName(), s);
        }

        for (PaImportStructure.SheetModel sm : structure.getSheets()) {
            if (!"IMPORT".equalsIgnoreCase(sm.getAction())) {
                continue;
            }
            PaExcelRaw.Sheet sheet = byName.get(sm.getSheetName());
            if (sheet == null) {
                draft.getReconciles().add(reconcile("BAD_CELL", sm.getSheetName(), null, sm.getSheetName(),
                        null, "sheetName"));
                continue;
            }
            draft.getPosts().add(assemblePost(sheet, sm, structure.getBands(), draft));
        }

        applyWeightScale(draft);
        return draft;
    }

    // ================================================================== 列映射装配（首选路径）

    /**
     * 按「列映射」装配：模型只说哪列是什么，内容全部由这里按坐标读原文。
     *
     * <p><b>为什么要有这条路</b>：老的逐格路径要求模型把每个格子都抄一遍。文件里有 25 张表时
     * 输出量必然吃穿 max_tokens（实测被截断，同一份代码单张表就能成），用户看到的是
     * 「模型没能读懂这份文件的结构」—— 其实模型读懂了，只是说不完。</p>
     *
     * <p>列映射下模型每表只答十几行（表头行、数据行范围、各列字母），25 张表一次调用就能给完。
     * 而且模型全程不输出原文，也就<b>不可能改写原文</b> —— 校对那一层在这里天然不成立。</p>
     *
     * <p>排版认不出来的表（{@code layout=OTHER}）不在这里处理，调用方应对那几张单独走
     * {@link #assemble}（逐格识别，单张输出量够）。</p>
     */
    public PaImportDraft assembleColumns(PaExcelRaw raw, PaImportColumnMap columnMap) {
        PaImportDraft draft = new PaImportDraft();
        Map<String, PaExcelRaw.Sheet> byName = new LinkedHashMap<>();
        for (PaExcelRaw.Sheet s : raw.getSheets()) {
            byName.put(s.getName(), s);
        }

        for (PaImportColumnMap.SheetMap sm : columnMap.getSheets()) {
            if (!"IMPORT".equalsIgnoreCase(sm.getAction())) {
                continue;
            }
            PaExcelRaw.Sheet sheet = byName.get(sm.getSheetName());
            if (sheet == null) {
                draft.getReconciles().add(reconcile("BAD_CELL", sm.getSheetName(), null,
                        sm.getSheetName(), null, "sheetName"));
                continue;
            }
            draft.getPosts().add(assemblePostByColumns(sheet, sm, draft));
        }

        applyWeightScale(draft);
        return draft;
    }

    /** 合并两份草稿。权重口径按岗位各自折算过，所以直接拼不会有口径冲突 */
    public void mergeInto(PaImportDraft target, PaImportDraft other) {
        if (other == null) {
            return;
        }
        target.getPosts().addAll(other.getPosts());
        target.getReconciles().addAll(other.getReconciles());
    }

    /** 按列映射装配一个岗位 */
    private PaImportDraft.PostDraft assemblePostByColumns(PaExcelRaw.Sheet sheet,
                                                          PaImportColumnMap.SheetMap sm,
                                                          PaImportDraft draft) {
        PaImportDraft.PostDraft post = new PaImportDraft.PostDraft();
        post.setSheetName(sheet.getName());

        // 岗位名：有专门坐标就读原文；没坐标用 sheet 名 —— 表内标题行几乎都叫
        // 「XX能力素质模型」，剥掉后缀就是岗位名，剥完为空则保留 sheet 名
        String rawPostName = readCell(sheet, sm.getPostNameCell());
        String postName = rawPostName == null ? normalizePostName(sheet.getName())
                : normalizePostName(rawPostName);
        post.setPostName(postName == null || postName.isBlank() ? sheet.getName() : postName);

        String deptName = readCell(sheet, sm.getDepartmentCell());
        if (deptName != null) {
            post.setDepartmentFromFile(true);
            post.setDepartmentName(deptName);
        }

        PaImportColumnMap.Columns cols = sm.getColumns() == null
                ? new PaImportColumnMap.Columns() : sm.getColumns();
        int firstRow = sm.getFirstDataRow() == null ? 1 : Math.max(1, sm.getFirstDataRow());
        int lastRow = sm.getLastDataRow() == null ? sheet.getLastRow()
                : Math.max(firstRow, sm.getLastDataRow());

        List<LevelSpec> levels = levelSpecs(sheet, sm, cols);

        Map<String, PaImportDraft.CategoryDraft> categories = new LinkedHashMap<>();
        for (int row = firstRow; row <= lastRow; row++) {
            PaImportDraft.ItemDraft item = itemAt(sheet, cols, levels, row, draft);
            if (item == null) {
                continue;
            }
            item.setSort(post.getItems().size());
            post.getItems().add(item);
            String category = item.getCategoryName();
            if (category != null && !category.isBlank()) {
                categories.computeIfAbsent(category.trim(), k -> {
                    PaImportDraft.CategoryDraft c = new PaImportDraft.CategoryDraft();
                    c.setCategoryName(k);
                    c.setSort(categories.size());
                    return c;
                });
            }
        }
        post.getCategories().addAll(categories.values());
        post.getBands().addAll(buildBands(null, post));
        return post;
    }

    /** 一行能力项；这一行没有能力项名（空行/分隔行/脚注）返回 null */
    private PaImportDraft.ItemDraft itemAt(PaExcelRaw.Sheet sheet, PaImportColumnMap.Columns cols,
                                           List<LevelSpec> levels, int row, PaImportDraft draft) {
        String name = readCell(sheet, cols.getItemName(), row);
        if (name == null) {
            return null;
        }
        // 表尾常有「备注：…」「合计」这类行：能力项名一列命中但整行明显不是能力项。
        // 只拦「合计/小计/备注/说明」这几种确定不是能力项开头的写法，别的交给校验器
        if (isSummaryRow(name)) {
            return null;
        }

        PaImportDraft.ItemDraft item = new PaImportDraft.ItemDraft();
        item.setCategoryName(readCell(sheet, cols.getCategory(), row));
        item.setItemCode(readCell(sheet, cols.getItemCode(), row));
        item.setItemName(name);
        item.setDefinition(readCell(sheet, cols.getDefinition(), row));
        item.setSourceRow(row);

        String weightCell = cellAt(cols.getWeight(), row);
        String weightText = readCell(sheet, cols.getWeight(), row);
        BigDecimal weight = parseDecimal(weightText);
        if (weightText != null && weight == null) {
            draft.getReconciles().add(reconcile("REWRITTEN", sheet.getName(), weightCell,
                    weightText, null, "weight"));
        }
        item.setWeight(weight);

        String raterText = readCell(sheet, cols.getRater(), row);
        // 原文无论认不认得出都留着：「内部客户360」和「跨部门360」拆成开关后长得一样，
        // 页面上要能看出当初写的是哪一种
        item.setRaterText(raterText);
        int[] rater = parseRater(raterText);
        if (rater == null && raterText != null) {
            // 识别不了不猜默认值：默认成 LEADER 等于替客户决定了评级关系
            draft.getReconciles().add(reconcile("REWRITTEN", sheet.getName(),
                    cellAt(cols.getRater(), row), raterText, null, "rater"));
            item.setRaterLeader(-1);
            item.setRater360(-1);
        } else if (rater != null) {
            item.setRaterLeader(rater[0]);
            item.setRater360(rater[1]);
        }

        for (LevelSpec spec : levels) {
            PaImportDraft.LevelDraft lv = new PaImportDraft.LevelDraft();
            lv.setLevelName(spec.name);
            lv.setScore(spec.score);
            lv.setLevelDesc(readCell(sheet, spec.col, row));
            lv.setSort(item.getLevels().size());
            item.getLevels().add(lv);
        }
        return item;
    }

    /**
     * 档位列表：一列一档，档名和分值从<b>该列表头</b>解析。
     *
     * <p>表头一律回 Excel 读，不采信模型抄的那份 —— 模型要是在这里把「2分 · 胜任/熟手」
     * 抄成「2分」，分值还在但档名就丢了，而这个错用户很难发现。只有表头格读不到时
     * 才退回模型给的 header。</p>
     *
     * <p>解析不出分值时按列序给 1、2、3…：这是事实上的通用约定（第一档 1 分），
     * 而档位分值解析不出来再生成一堆问题问用户，只会让人放弃导入。</p>
     */
    private List<LevelSpec> levelSpecs(PaExcelRaw.Sheet sheet, PaImportColumnMap.SheetMap sm,
                                       PaImportColumnMap.Columns cols) {
        List<LevelSpec> out = new ArrayList<>();
        if (cols.getLevels() == null) {
            return out;
        }
        int headerRow = sm.getHeaderRow() == null ? 0 : sm.getHeaderRow();
        for (PaImportColumnMap.LevelColumn lc : cols.getLevels()) {
            if (lc == null || lc.getCol() == null || lc.getCol().isBlank()) {
                continue;
            }
            String header = headerRow > 0 ? readCell(sheet, lc.getCol(), headerRow) : null;
            if (header == null) {
                header = lc.getHeader();
            }
            BigDecimal score = parseScore(header);
            String name = stripScorePrefix(header);
            if (score == null) {
                score = BigDecimal.valueOf(out.size() + 1L);
                if (name == null || name.isBlank()) {
                    name = "第 " + (out.size() + 1) + " 档";
                }
                log.warn("表「{}」列 {} 的表头「{}」解析不出分值，按列序记为 {} 分",
                        sheet.getName(), lc.getCol(), header, score);
            }
            LevelSpec spec = new LevelSpec();
            spec.col = lc.getCol().toUpperCase();
            spec.score = score;
            spec.name = name;
            out.add(spec);
        }
        return out;
    }

    /** 「1分 · 入门/新手」→「入门/新手」；剥完为空返回原串 */
    static String stripScorePrefix(String header) {
        if (header == null) {
            return null;
        }
        String s = header.trim()
                .replaceFirst("^\\s*\\d+(?:\\.\\d+)?\\s*分?\\s*", "")
                .replaceFirst("^[·・\\-—–:：/|｜、\\s]+", "")
                .trim();
        return s.isEmpty() ? header.trim() : s;
    }

    /**
     * 「合计」「备注：…」「注：…」这类不是能力项的行。
     *
     * <p>只认「整格就是一个标记词」或「标记词后面直接跟冒号/括号/空白」两种写法。
     * 用 startsWith 一刀切会把「合计能力的运用」这种正常能力项名也拦掉 ——
     * 少导一个能力项，用户很难发现。</p>
     */
    static boolean isSummaryRow(String itemName) {
        String s = itemName.trim();
        // 整格只是一个标记词，或者标记词后面紧跟冒号/括号/顿号/空白 —— 这两种才是脚注行。
        // 「合计能力的运用」这种正常能力项名不能拦（少导一个能力项，用户很难发现）
        return s.matches("^(合计|小计|总计|备注|说明|注)$")
                || s.matches("^(合计|小计|总计|备注|说明|注)[:：\\-—）)、，,.。\\s].*");
    }

    /** 单档位的列坐标、分值、档名 */
    private static final class LevelSpec {
        private String col;
        private BigDecimal score;
        private String name;
    }

    /** 按列字母取某行的值；列字母为空返回 null */
    private static String readCell(PaExcelRaw.Sheet sheet, String col, int row) {
        if (col == null || col.isBlank() || row <= 0) {
            return null;
        }
        return sheet.valueAt(row, col.trim().toUpperCase());
    }

    /** 按坐标（如 "E4"）取原文；坐标为空返回 null */
    private static String readCell(PaExcelRaw.Sheet sheet, String cell) {
        int[] pos = PaExcelRaw.Sheet.splitCell(cell);
        if (pos == null) {
            return null;
        }
        return sheet.valueAt(pos[0], PaExcelRaw.Sheet.columnLetter(pos[1]));
    }

    private static String cellAt(String col, int row) {
        return col == null || col.isBlank() || row <= 0
                ? null : col.trim().toUpperCase() + row;
    }

    // ------------------------------------------------------------------ 单岗位

    private PaImportDraft.PostDraft assemblePost(PaExcelRaw.Sheet sheet,
                                                 PaImportStructure.SheetModel sm,
                                                 PaImportStructure.BandsModel structureBands,
                                                 PaImportDraft draft) {
        PaImportDraft.PostDraft post = new PaImportDraft.PostDraft();
        post.setSheetName(sheet.getName());
        post.setPostName(normalizePostName(
                claim(sheet, sm.getPostNameCell(), sm.getPostName(), draft, "postName")));

        if (sm.getDepartment() != null && sm.getDepartment().hasValue()) {
            post.setDepartmentFromFile(true);
            post.setDepartmentName(claim(sheet, sm.getDepartment().getCell(),
                    sm.getDepartment().getValue(), draft, "department"));
        }

        // 类别：按在文件里首次出现的顺序定 sort。不按字母序 —— 客户排的先后本身有含义
        Map<String, PaImportDraft.CategoryDraft> categories = new LinkedHashMap<>();
        for (PaImportStructure.ItemModel it : sm.getItems()) {
            String name = claim(sheet, cellOf(it.getCategory()), valueOf(it.getCategory()), draft, "category");
            if (name == null || name.isBlank()) {
                continue;
            }
            categories.computeIfAbsent(name.trim(), k -> {
                PaImportDraft.CategoryDraft c = new PaImportDraft.CategoryDraft();
                c.setCategoryName(k);
                c.setSort(categories.size());
                return c;
            });
        }
        post.getCategories().addAll(categories.values());

        for (int i = 0; i < sm.getItems().size(); i++) {
            PaImportDraft.ItemDraft item = assembleItem(sheet, sm.getItems().get(i), draft, i);
            if (item != null) {
                post.getItems().add(item);
            }
        }

        post.getBands().addAll(buildBands(sm.getBands() != null ? sm.getBands() : structureBands, post));
        return post;
    }

    private PaImportDraft.ItemDraft assembleItem(PaExcelRaw.Sheet sheet,
                                                 PaImportStructure.ItemModel im,
                                                 PaImportDraft draft,
                                                 int index) {
        PaImportDraft.ItemDraft item = new PaImportDraft.ItemDraft();
        item.setCategoryName(claim(sheet, cellOf(im.getCategory()), valueOf(im.getCategory()), draft, "category"));
        item.setItemCode(claim(sheet, cellOf(im.getItemCode()), valueOf(im.getItemCode()), draft, "itemCode"));
        item.setItemName(claim(sheet, cellOf(im.getItemName()), valueOf(im.getItemName()), draft, "itemName"));
        item.setDefinition(claim(sheet, cellOf(im.getDefinition()), valueOf(im.getDefinition()), draft, "definition"));
        item.setSort(index);
        item.setSourceRow(rowOf(im.getItemName()));

        String weightText = claim(sheet, cellOf(im.getWeight()), valueOf(im.getWeight()), draft, "weight");
        BigDecimal weight = parseDecimal(weightText);
        if (weight == null) {
            draft.getReconciles().add(reconcile("REWRITTEN", sheet.getName(),
                    cellOf(im.getWeight()), valueOf(im.getWeight()), weightText, "weight"));
        }
        item.setWeight(weight);

        String raterText = claim(sheet, cellOf(im.getRater()), valueOf(im.getRater()), draft, "rater");
        item.setRaterText(raterText);
        int[] rater = parseRater(raterText);
        if (rater == null) {
            // 识别不了的取值不猜成默认值：默认成 LEADER 等于替客户改了评级关系
            draft.getReconciles().add(reconcile("REWRITTEN", sheet.getName(),
                    cellOf(im.getRater()), valueOf(im.getRater()), raterText, "rater"));
            item.setRaterLeader(-1);
            item.setRater360(-1);
        } else {
            item.setRaterLeader(rater[0]);
            item.setRater360(rater[1]);
        }

        for (PaImportStructure.LevelModel lm : im.getLevels()) {
            PaImportDraft.LevelDraft lv = new PaImportDraft.LevelDraft();
            String nameText = claim(sheet, cellOf(lm.getLevelName()), valueOf(lm.getLevelName()), draft, "levelName");
            String scoreText = claim(sheet, cellOf(lm.getScore()), valueOf(lm.getScore()), draft, "score");
            lv.setLevelName(nameText);
            lv.setScore(parseScore(scoreText));
            lv.setLevelDesc(claim(sheet, cellOf(lm.getLevelDesc()), valueOf(lm.getLevelDesc()), draft, "levelDesc"));
            lv.setSort(item.getLevels().size());
            item.getLevels().add(lv);
        }
        return item;
    }

    /**
     * 造分值区间。
     *
     * <p>默认 {@code FROM_LEVELS}：把该岗位所有档位的分值去重升序排列，
     * 第 i 个档得 {@code [score_i, score_{i+1})}，最后一档 {@code [score_last, +∞)}。
     * 业务口径就是「1~2 分是新手，5 分是专家」，所以区间上界取下一档的分值而不是 +1 ——
     * 某些模板档位分值不是 1/2/3/4/5（比如 10/20/30），取 +1 就错位了。</p>
     *
     * <p>区间名取档位名：档名形如「入门/新手」时取斜杠后的部分（与业务确认的展示口径一致），
     * 没有斜杠就用整个档名。</p>
     */
    private List<PaImportDraft.BandDraft> buildBands(PaImportStructure.BandsModel bandsModel,
                                                     PaImportDraft.PostDraft post) {
        List<PaImportDraft.BandDraft> bands = new ArrayList<>();

        if (bandsModel != null && !"FROM_LEVELS".equalsIgnoreCase(bandsModel.getMode())
                && !bandsModel.getEntries().isEmpty()) {
            // 表里另有一块区间表：按模型给的坐标原样取（这里只做数值化，取文字在 claim 阶段已完成）
            for (PaImportStructure.BandModel bm : bandsModel.getEntries()) {
                PaImportDraft.BandDraft b = new PaImportDraft.BandDraft();
                b.setBandName(bm.getBandName() == null ? null : bm.getBandName().getValue());
                b.setLowerBound(parseDecimal(bm.getLowerBound() == null ? null : bm.getLowerBound().getValue()));
                b.setUpperBound(parseDecimal(bm.getUpperBound() == null ? null : bm.getUpperBound().getValue()));
                b.setSort(bands.size());
                bands.add(b);
            }
            return bands;
        }

        // FROM_LEVELS：以「分值 → 档名」去重建表（同分值的多档取第一个出现的名字）
        Map<BigDecimal, String> byScore = new java.util.TreeMap<>();
        for (PaImportDraft.ItemDraft item : post.getItems()) {
            for (PaImportDraft.LevelDraft lv : item.getLevels()) {
                if (lv.getScore() == null) {
                    continue;
                }
                byScore.putIfAbsent(lv.getScore(), lv.getLevelName());
            }
        }
        List<Map.Entry<BigDecimal, String>> entries = new ArrayList<>(byScore.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            PaImportDraft.BandDraft b = new PaImportDraft.BandDraft();
            b.setLowerBound(entries.get(i).getKey());
            b.setUpperBound(i + 1 < entries.size() ? entries.get(i + 1).getKey() : null);
            b.setBandName(bandNameOf(entries.get(i).getValue()));
            b.setSort(i);
            bands.add(b);
        }
        return bands;
    }

    /**
     * 剥掉标题里的模型后缀：「技术人员能力素质模型」→「技术人员」。
     *
     * <p>表内标题行几乎都是「XX能力素质模型 / XX能力模型」这种写法，模型有时原样返回全文、
     * 有时已经剥好了。这里统一兜一层：剥完是空的就不剥（别把整个标题作成空串）。
     * 岗位名在确认页可改，所以这里做确定性清洗比让用户每次手删后缀划算。</p>
     */
    static String normalizePostName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        String stripped = s.replaceAll(
                "(能力素质模型|能力模型|素质模型|胜任力模型|能力素质|任职资格模型)$", "").trim();
        return stripped.isEmpty() ? s : stripped;
    }

    /** 「入门/新手」→「新手」；没有斜杠就原样 */
    static String bandNameOf(String levelName) {
        if (levelName == null || levelName.isBlank()) {
            return null;
        }
        String s = levelName.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('／'));
        if (slash >= 0 && slash + 1 < s.length()) {
            return s.substring(slash + 1).trim();
        }
        return s;
    }

    // ------------------------------------------------------------------ 权重口径

    /**
     * 权重口径判定：整批权重是「小数」（0.14）还是「百分数」（14）。
     *
     * <p>不按单行猜 —— 单个 {@code 1} 既可能是 1% 也可能是 100%。看整表合计落在哪边：
     * 合计 ≈ 1 → 小数，统一 ×100；合计 ≈ 100 → 本就是百分数。
     * 两边都不像就<b>不换算</b>，交给校验器报「权重合计异常」让人裁决。</p>
     */
    private void applyWeightScale(PaImportDraft draft) {
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            BigDecimal sum = BigDecimal.ZERO;
            int n = 0;
            for (PaImportDraft.ItemDraft item : post.getItems()) {
                if (item.getWeight() != null) {
                    sum = sum.add(item.getWeight());
                    n++;
                }
            }
            if (n == 0) {
                continue;
            }
            boolean likeOne = sum.subtract(SUM_ONE).abs().compareTo(new BigDecimal("0.03")) <= 0;
            boolean likeHundred = sum.subtract(SUM_HUNDRED).abs().compareTo(SUM_TOLERANCE) <= 0;
            if (likeOne && !likeHundred) {
                for (PaImportDraft.ItemDraft item : post.getItems()) {
                    if (item.getWeight() != null) {
                        item.setWeight(item.getWeight().multiply(SUM_HUNDRED).setScale(4, RoundingMode.HALF_UP));
                    }
                }
                log.info("导入岗位「{}」权重按小数口径识别，已 ×100（原合计 {}）", post.getPostName(), sum);
            } else if (!likeHundred) {
                log.warn("导入岗位「{}」权重合计 {} 既不像 1 也不像 100，不做换算，交校验器报错",
                        post.getPostName(), sum);
            }
        }
    }

    // ------------------------------------------------------------------ 取值与对账

    /**
     * 取一个字段的值：有坐标就回表读原文并和模型声称的对账，没坐标就用模型给的但记一笔。
     *
     * @return 最终采用的值（入库用）
     */
    private String claim(PaExcelRaw.Sheet sheet, String cell, String claimed,
                         PaImportDraft draft, String field) {
        if (cell == null || cell.isBlank()) {
            if (claimed != null && !claimed.isBlank()) {
                draft.getReconciles().add(reconcile("NO_CELL", sheet.getName(), null, claimed, null, field));
            }
            return claimed;
        }
        int[] pos = PaExcelRaw.Sheet.splitCell(cell);
        if (pos == null) {
            draft.getReconciles().add(reconcile("BAD_CELL", sheet.getName(), cell, claimed, null, field));
            return claimed;
        }
        String actual = sheet.valueAt(pos[0], PaExcelRaw.Sheet.columnLetter(pos[1]));
        if (actual == null) {
            if (claimed != null && !claimed.isBlank()) {
                draft.getReconciles().add(reconcile("EMPTY_CELL", sheet.getName(), cell, claimed, null, field));
            }
            return null;
        }
        if (claimed != null && !claimed.isBlank() && !sameText(claimed, actual)
                && !isSubsetOf(claimed, actual)) {
            draft.getReconciles().add(reconcile("REWRITTEN", sheet.getName(), cell, claimed, actual, field));
        }
        // 一律用表里的原文：模型那份只当线索，不当内容。
        // 唯一例外是「格子里装了多个语义、模型只抽了其中一段」——那由调用方从原文里取需要的部分。
        return actual;
    }

    /**
     * 模型抽的值是不是原文里连续的一段。
     *
     * <p>典型场景：表头格写着「2分 · 胜任/熟手」，模型抽分值报 2、抽档名报「胜任/熟手」。
     * 这两个都不是「改写原文」，而是在一格多功能地把其中一段拿出来 —— 不算错误，
     * 否则每一份带说明表头的模板都会刷出一堆假警报，用户就学会无视所有提示了。</p>
     */
    static boolean isSubsetOf(String claimed, String actual) {
        return !normalize(claimed).isEmpty() && normalize(actual).contains(normalize(claimed));
    }

    /** 文本等价比较：忽略首尾空白、连续空白与换行差异 —— 这些不算「改写」 */
    static boolean sameText(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim();
    }

    private static PaImportDraft.Reconcile reconcile(String kind, String sheetName, String cell,
                                                     String claimed, String actual, String field) {
        PaImportDraft.Reconcile r = new PaImportDraft.Reconcile();
        r.setKind(kind);
        r.setSheetName(sheetName);
        r.setCell(cell);
        r.setClaimed(claimed);
        r.setActual(actual);
        r.setField(field);
        return r;
    }

    private static String cellOf(PaImportStructure.CellValue v) {
        return v == null ? null : v.getCell();
    }

    private static String valueOf(PaImportStructure.CellValue v) {
        return v == null ? null : v.getValue();
    }

    /** "E4" → 4 */
    private static Integer rowOf(PaImportStructure.CellValue v) {
        if (v == null || v.getCell() == null) {
            return null;
        }
        int[] pos = PaExcelRaw.Sheet.splitCell(v.getCell());
        return pos == null ? null : pos[0];
    }

    // ------------------------------------------------------------------ 数值解析

    /** "0.14" → 0.14；"14%" → 14；"14" → 14；解析不了返回 null */
    static BigDecimal parseDecimal(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().replace("％", "%").replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        boolean percent = s.endsWith("%");
        if (percent) {
            s = s.substring(0, s.length() - 1).trim();
        }
        try {
            BigDecimal v = new BigDecimal(s);
            return percent ? v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 分值解析：容忍「1分 · 入门/新手」这种把说明写在同一格的表头模板。
     * 取串里的第一个数字。
     */
    static BigDecimal parseScore(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)").matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return new BigDecimal(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 评级主体解析。
     *
     * @return {leader, rater360}；识别不了返回 null（<b>不给默认值</b> — 默认成上级等于替客户改评级关系）
     */
    static int[] parseRater(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.replaceAll("\\s+", "").toLowerCase()
                .replace("３６０", "360");
        boolean leader = t.contains("上级") || t.contains("领导") || t.contains("leader")
                || t.contains("主管");
        boolean r360 = t.contains("360") || t.contains("rater360") || t.contains("测评审")
                || t.contains("测评人");
        if (!leader && !r360) {
            return null;
        }
        return new int[]{leader ? 1 : 0, r360 ? 1 : 0};
    }
}
