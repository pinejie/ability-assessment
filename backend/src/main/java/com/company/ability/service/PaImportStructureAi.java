package com.company.ability.service;

import com.company.ability.client.PaImportAiClient;
import com.company.ability.dto.PaExcelRaw;
import com.company.ability.dto.PaImportColumnMap;
import com.company.ability.dto.PaImportOverview;
import com.company.ability.dto.PaImportStructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构识别器：让模型给出「列映射」，或（兜底时）给出逐格结构。
 *
 * <p><b>两条路径，优先级明确：</b></p>
 *
 * <ol>
 *   <li>{@link #detectColumns} —— <b>首选</b>。让模型只回答「第几行是表头、第几列是能力项、
 *       第几列是 1 分档」，不抄任何内容。输出量是每表十几行，25 张表一次调用就能给完。
 *       内容由 {@code PaImportDraftAssembler} 按列坐标回 Excel 逐行读原文。</li>
 *   <li>{@link #detect} —— <b>兜底</b>。模型说某张表排版认不出来（{@code layout=OTHER}）时，
 *       对那一张单独走老的逐格识别。单张表的输出量远在额度内（实测 15 个能力项可以完整返回）。</li>
 * </ol>
 *
 * <p><b>为什么不能只有第 2 条</b>：老路径要求模型把每个格子都抄一遍，25 张表时
 * 输出必然超出 max_tokens（实测 2026-11-22：25 张表被截断，同样代码单张成功）。
 * 用户看到的报错是「模型没能读懂这份文件的结构」，其实是额度吃穿，不是模型不懂。</p>
 *
 * <p><b>为什么不能只有第 1 条</b>：竖排（一个能力项跨多行、每行一档）和横排（能力项在行、
 * 档位在列）用列映射表达不了。这类表交给逐格识别，虽然慢，但能正确处理。</p>
 */
@Service
public class PaImportStructureAi {

    private static final Logger log = LoggerFactory.getLogger(PaImportStructureAi.class);

    /** 每列送几行样例：3 行足够看出这一列是文字、数字还是档位描述 */
    private static final int SAMPLES_PER_COLUMN = 3;

    private static final String OVERVIEW_SYSTEM = """
            你是 Excel 结构识别器。你的任务是在一组工作表里，判断哪些是"岗位能力素质模型表"。
            你只输出 JSON，不输出任何解释、不加 markdown 代码围栏。
            """;

    private static final String SYSTEM = """
            你是 Excel 结构识别器。你的唯一任务是把一张岗位能力素质模型表格的"排版"翻译成结构化 JSON。
            你只输出 JSON，不输出任何解释、不加 markdown 代码围栏。
            """;

    private static final String COLUMN_SYSTEM = """
            你是 Excel 排版识别器。你的任务只有一件事：看出一张岗位能力素质模型表里
            「第几行是表头、每一列分别是什么」，然后输出列坐标。

            你**不要**抄写任何单元格的内容，也不要总结表格里写了什么 ——
            内容会由程序按你给的列坐标自己去读。你只需要说清楚位置。

            你只输出 JSON，不输出任何解释、不加 markdown 代码围栏。
            """;

    private final PaImportAiClient ai;
    private final ObjectMapper json;

    public PaImportStructureAi(PaImportAiClient ai, ObjectMapper json) {
        this.ai = ai;
        this.json = json;
    }

    /**
     * 文件总览：只给「sheet 名 + 前两行」，让模型在一轮里判断哪些是岗位模型、岗位叫什么。
     *
     * <p>输出只有几十行，与表数无关，所以 25 张表也不会超额度。</p>
     */
    public PaImportOverview overview(PaExcelRaw raw) {
        if (!ai.available()) {
            log.warn("AI 网关未配置，跳过文件总览，全部 sheet 交用户手工勾选");
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("""
                任务：这是一份 Excel 的全部工作表。请判断每张表是不是"岗位能力素质模型表"，
                并给出它的岗位名。

                ## 输出格式（严格遵守）

                {"sheets":[{"sheetName":"<原样>","action":"IMPORT|IGNORE","postName":"<岗位名>","reason":"<一句话依据>"}],
                 "notes":["<整体观察，如「部分表列数不一致」>"]}

                ## 判定标准

                - IMPORT：表里有「能力项/行为描述/权重/评价主体/分值档位」这类列，是某个岗位的能力模型
                - IGNORE：说明页、目录、修订记录、汇总统计等不是岗位模型的表
                - postName：优先取表内标题行里的岗位名（如「技术人员能力素质模型」→「技术人员」），
                  没有标题行就用 sheet 名
                - 只输出 JSON，不要解释、不要代码围栏

                ## 各工作表（名称 + 前两行原文）
                """);
        for (PaExcelRaw.Sheet sheet : raw.getSheets()) {
            sb.append("\n### 「").append(sheet.getName()).append("」");
            sb.append("（非空行 ").append(sheet.getRows().size())
                    .append("，最大列 ").append(sheet.getLastColumn()).append("）\n");
            int shown = 0;
            for (Map.Entry<Integer, Map<String, String>> row : sheet.getRows().entrySet()) {
                if (shown++ >= 2) {
                    break;
                }
                sb.append("  行").append(row.getKey()).append("：").append(jsonRow(row.getValue())).append("\n");
            }
        }

        try {
            String text = ai.complete(OVERVIEW_SYSTEM, sb.toString());
            PaImportOverview parsed = json.readValue(PaImportAiClient.stripToJson(text), PaImportOverview.class);
            log.info("文件总览完成：{} 张 sheet，IMPORT {} 张", parsed.getSheets().size(),
                    parsed.getSheets().stream().filter(s -> "IMPORT".equalsIgnoreCase(s.getAction())).count());
            return parsed;
        } catch (Exception e) {
            log.warn("文件总览失败，降级为手工勾选：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 列映射识别（首选路径）：模型只说「哪列是什么」，不抄内容。
     *
     * <p>输入刻意压得很小：表头行 + 每列前 3 个样例 + 行数。给全量正文既没必要
     * （模型只回答位置），也会让它顺手改写文本。</p>
     *
     * @return 解析出的列映射；模型不可用或失败时返回 null，调用方降级
     */
    public PaImportColumnMap detectColumns(PaExcelRaw raw) {
        if (!ai.available()) {
            log.warn("AI 网关未配置，跳过列映射识别，导入走手工映射");
            return null;
        }
        try {
            String text = ai.complete(COLUMN_SYSTEM, buildColumnPrompt(raw));
            PaImportColumnMap parsed = json.readValue(PaImportAiClient.stripToJson(text),
                    PaImportColumnMap.class);
            log.info("列映射识别完成：sheets={}, notes={}",
                    parsed.getSheets() == null ? 0 : parsed.getSheets().size(),
                    parsed.getNotes() == null ? 0 : parsed.getNotes().size());
            return parsed;
        } catch (Exception e) {
            log.warn("列映射识别失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 逐格识别（兜底路径）：要求模型把每个格子的内容都输出。
     *
     * <p><b>只用于单张表</b>：25 张表会超出输出额度（实测被截断），单张则绰绰有余。
     * 调用方应按表逐张调用本方法，而不是把整份文件塞进来。</p>
     *
     * @return 解析出的结构；模型不可用或失败时返回 null
     */
    public PaImportStructure detect(PaExcelRaw raw) {
        if (!ai.available()) {
            log.warn("AI 网关未配置，跳过结构识别，导入走手工映射");
            return null;
        }
        String user = buildPrompt(raw);
        try {
            String text = ai.complete(SYSTEM, user);
            PaImportStructure parsed = json.readValue(PaImportAiClient.stripToJson(text),
                    PaImportStructure.class);
            log.info("结构识别完成：sheets={}, 备注={}",
                    parsed.getSheets() == null ? 0 : parsed.getSheets().size(),
                    parsed.getNotes() == null ? 0 : parsed.getNotes().size());
            return parsed;
        } catch (Exception e) {
            log.warn("结构识别失败，降级为手工映射：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 列映射提示词

    /**
     * 构造列映射提示词：给表头 + 每列样例，要求只回答坐标。
     *
     * <p>样例取值有讲究：<b>不能只给</b>表头那行。档位列的表头（「1分 · 入门/新手」）和
     * 数据行（实际的行为描述）长相完全不同，只看表头模型容易把档位列当成普通文本列。
     * 给前 3 个非空值，长度截到 40 字 —— 够看出「这一列是描述还是数字」，又不至于把正文塞爆。</p>
     */
    String buildColumnPrompt(PaExcelRaw raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                任务：识别下面每张工作表的列结构，输出列坐标 JSON。

                ## 输出格式（严格遵守，字段名不得改）

                {"sheets":[{
                  "sheetName":"<工作表名，原样>",
                  "action":"IMPORT|IGNORE",
                  "layout":"ROW_PER_ITEM|OTHER",
                  "headerRow":<表头所在行号，整数>,
                  "firstDataRow":<第一个能力项所在行号，整数>,
                  "lastDataRow":<最后一个能力项所在行号，整数；不确定就 null>,
                  "postNameCell":"<岗位名所在格，如 A1；没有标题行就 null>",
                  "departmentCell":"<部门所在格；文件没写部门就 null>",
                  "columns":{
                    "category":"<列字母>", "itemCode":"<列字母或 null>",
                    "itemName":"<列字母>", "definition":"<列字母或 null>",
                    "weight":"<列字母>", "rater":"<列字母>",
                    "levels":[{"col":"<列字母>","header":"<该列表头原文>"}]
                  },
                  "notes":["<这张表拿不准的地方>"]
                }],
                "notes":["<整体观察>"]}

                ## 关键规则

                1. **只输出坐标和表头原文，不要抄数据行的内容。** 内容程序会自己读。
                2. **levels 按分值从低到高排列**，一项一列。本次常见的是 5 档占 5 列
                   （如 E~I 列，表头写着「1分 · 入门/新手」…「5分 · 开创/专家」）。
                   header 必须原样抄该列表头，程序要靠它解析分值和档名，抄错就解析不出来。
                3. **layout 判定**：
                   - `ROW_PER_ITEM`：一行就是一个能力项的完整信息，各档描述横向排开 ← 绝大多数表是这种
                   - `OTHER`：一个能力项占多行（每行一档）、或能力项竖排在行、档位横排在列、
                     或你根本看不出各列含义。**判断不了就填 OTHER**，程序会对这张表改用逐格识别，
                     填错比填 OTHER 更糟。
                4. **headerRow 与 firstDataRow 的区别**：表头是「能力项/权重/1分 · 入门/新手」那一行，
                   数据行是「L1 知人善用 …」那种。很多表在标题行和表头行之间隔着一个空行，
                   别把标题行（「XX能力素质模型」）当表头。
                5. **category 通常是合并单元格**：只在每块第一行有值。照实给列字母即可，
                   程序会向上沿用。不要因为「下面几行是空的」就说这列没有值。
                6. 不是岗位模型的表（说明页、目录、修订记录）action 填 `IGNORE`，其余字段可省略。
                7. notes 里写你拿不准的地方（如「J 列像权重但也像排序」「表头有两个合并行」），
                   程序会转成待确认项问用户。

                ## 待识别的表格
                """);

        for (PaExcelRaw.Sheet sheet : raw.getSheets()) {
            sb.append("\n### 工作表「").append(sheet.getName()).append("」\n");
            sb.append("- 非空行数：").append(sheet.getRows().size())
                    .append("，最大行号：").append(sheet.getLastRow())
                    .append("，最大列：").append(sheet.getLastColumn()).append("\n");
            if (!sheet.getMerges().isEmpty()) {
                sb.append("- 合并区域：").append(String.join("、", sheet.getMerges())).append("\n");
            }
            sb.append("- 每列样例（列字母 | 该列前 ").append(SAMPLES_PER_COLUMN).append(" 个非空值）：\n");
            for (Map.Entry<String, List<String>> col : columnSamples(sheet).entrySet()) {
                sb.append("  ").append(col.getKey()).append(" | ")
                        .append(String.join(" ｜ ", col.getValue())).append("\n");
            }
            // 行号必须给全：模型要报 firstDataRow / lastDataRow，少行它会按样例数瞎报
            sb.append("- 各行行号（只需知道边界，不必读内容）：")
                    .append(joinRowNumbers(sheet)).append("\n");
        }
        return sb.toString();
    }

    /** 行号列表，用于让模型报准 firstDataRow / lastDataRow */
    private String joinRowNumbers(PaExcelRaw.Sheet sheet) {
        List<String> nums = new ArrayList<>();
        for (Integer row : sheet.getRows().keySet()) {
            nums.add(String.valueOf(row));
        }
        return String.join("、", nums);
    }

    // ------------------------------------------------------------------ 逐格提示词

    /**
     * 构造逐格识别提示词（兜底路径）。
     *
     * <p>契约写得很死（字段名、取值、坐标格式），因为模型自由发挥的部分越多，
     * 反序列化就越容易失败，用户看到的就是「导入坏了」。</p>
     */
    String buildPrompt(PaExcelRaw raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                任务：识别下面每张工作表的排版，输出 JSON。

                ## 输出格式（严格遵守，字段名不得改）

                {"sheets":[{
                  "sheetName":"<工作表名，原样>",
                  "action":"IMPORT|IGNORE",
                  "postName":"<岗位名>", "postNameCell":"<坐标，如 B1；没写就 null>",
                  "department":{"value":"<部门原文>","cell":"<坐标>"},
                  "items":[{
                    "category":  {"value":"<能力类别>","cell":"<坐标>"},
                    "itemCode":  {"value":"<编号>","cell":"<坐标>"},
                    "itemName":  {"value":"<名称>","cell":"<坐标>"},
                    "definition":{"value":"<定义>","cell":"<坐标>"},
                    "weight":    {"value":"<权重原文>","cell":"<坐标>"},
                    "rater":     {"value":"<评价主体原文>","cell":"<坐标>"},
                    "levels":[{
                      "levelName": {"value":"<档名>","cell":"<坐标>"},
                      "score":     {"value":"<分值>","cell":"<坐标>"},
                      "levelDesc": {"value":"<行为描述>","cell":"<坐标>"}
                    }]
                  }]
                }],
                "bands":{"mode":"FROM_LEVELS|FROM_SHEET|MANUAL","entries":[]},
                "notes":["<你拿不准的地方，用人话写>"]}

                ## 规则

                1. **每张表的首个非空行的 sheet 名**当作岗位名；如果表里有单独的标题行写着
                   「XX能力素质模型」，取「XX」并给出该格坐标；都没有就给 null。
                2. **items 必须覆盖表里的每一个能力项，一个都不能少、不能抽样**。
                   数据行数在上文「全部非空行原文」里已经给齐，items 的数量要对得上。
                3. **value 是给代码核对用的，必须与原文一致**（含标点空格）：
                   - 原文在上文被截断（末尾带 …）时，value **留空**，只给 cell
                   - 长文本（定义、行为描述）value 可以留空，只给 cell
                   - 从同一个表头格里同时抽「分值」和「档名」时（如「2分 · 胜任/熟手」），
                     分值 value 写「2」、档名 value 写「胜任/熟手」，两个 cell 都写同一格（如 F3）——
                     这种情况按「原文包含」判定，不算错误
                4. **cell 必须是 Excel 原生坐标**（列字母+行号，如 E4）。不许省略，不许用「第4行第5列」。
                5. **合并单元格**：某列的值按块合并（例如 A4:A5 合并且只有 A4 有值）时，
                   把该块里**每一行**的 category 都填上同一个值，cell 都写左上角那格（A4）。
                6. **等级列**：一列一档时，levels 的 levelName 取该列表头里的档名、
                   levelDesc 取该行该列的正文；一个能力项跨多行、每行一档时，
                   每行的档名与分值取同一行的「档级/分值」列、描述取「行为描述」列，
                   cell 填**该行**对应的坐标。
                7. **权重**：原样输出表格里的写法（0.14 就写 "0.14"，14% 就写 "14%"），不要换算。
                8. **评价主体**：原样输出（「直接上级」「360」「直接上级+360」等），不要翻译成英文。
                9. **不是岗位模型的 sheet**（说明页、修订记录、目录页）action 填 IGNORE。
                10. **bands**：如果表里没有单独的区间表，mode 填 FROM_LEVELS、entries 留空，
                   我会按「分值 N → [N, 下一档) 最后一档 +∞」自动生成。
                11. notes 里写你自己不确定的地方（比如「B 列像编号但也像排序」「第 7 行的评价主体写法不标准」），
                    我会拿去问用户。

                ## 待识别的表格
                """);

        for (PaExcelRaw.Sheet sheet : raw.getSheets()) {
            sb.append("\n### 工作表「").append(sheet.getName()).append("」\n");
            sb.append("- 非空行数：").append(sheet.getRows().size())
                    .append("，最大行号：").append(sheet.getLastRow())
                    .append("，最大列：").append(sheet.getLastColumn()).append("\n");
            if (!sheet.getMerges().isEmpty()) {
                sb.append("- 合并区域：").append(String.join("、", sheet.getMerges())).append("\n");
            }
            sb.append("- 各列样例（列字母 | 前 ").append(SAMPLES_PER_COLUMN).append(" 个非空值）：\n");
            for (Map.Entry<String, List<String>> col : columnSamples(sheet).entrySet()) {
                sb.append("  ").append(col.getKey()).append(" | ")
                        .append(String.join(" ｜ ", col.getValue())).append("\n");
            }
            // 必须给全部行：只给前几行时模型会照着样例输出同样多的能力项（实测只给 3 行它就只吐 2 项）。
            // 每格截到 60 字，但行一个不少 —— 模型要的是「有几行、每行在哪」，不是全文。
            sb.append("- 全部非空行原文（长文本已截断，末尾带 …）：\n");
            for (Map.Entry<Integer, Map<String, String>> row : sheet.getRows().entrySet()) {
                sb.append("  行").append(row.getKey()).append("：")
                        .append(jsonRow(row.getValue())).append("\n");
            }
        }
        return sb.toString();
    }

    /** 每列取前 N 个非空值（超长截断），让模型看出这一列的「长相」 */
    private Map<String, List<String>> columnSamples(PaExcelRaw.Sheet sheet) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        int cols = PaExcelRaw.Sheet.columnIndex(sheet.getLastColumn()) + 1;
        for (int c = 0; c < cols; c++) {
            String letter = PaExcelRaw.Sheet.columnLetter(c);
            List<String> values = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, String>> row : sheet.getRows().entrySet()) {
                if (values.size() >= SAMPLES_PER_COLUMN) {
                    break;
                }
                String v = sheet.rawAt(row.getKey(), letter);
                if (v != null) {
                    values.add(v.length() <= 40 ? v : v.substring(0, 40) + "…");
                }
            }
            if (!values.isEmpty()) {
                out.put(letter, values);
            }
        }
        return out;
    }

    private String jsonRow(Map<String, String> cells) {
        try {
            Map<String, String> cut = new LinkedHashMap<>();
            cells.forEach((k, v) -> cut.put(k, v.length() <= 60 ? v : v.substring(0, 60) + "…"));
            return json.writeValueAsString(cut);
        } catch (Exception e) {
            return cells.toString();
        }
    }
}
