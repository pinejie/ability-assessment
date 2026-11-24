package com.company.ability.service;

import com.company.ability.dto.PaExcelRaw;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Excel 无损读取（能力结构导入的第 ① 步）。
 *
 * <p><b>刻意什么都不猜</b>：不认识表头、不判断哪行是数据、不推断类型 —— 只把每格原文读出来。
 * 「这张表怎么排的」交给模型，模型给坐标，再由 {@code PaImportDraftAssembler} 按坐标回读原文。
 * 这样模型永远没有机会改写能力项描述那 80 段长文本。</p>
 *
 * <p>几个必须做对的地方：</p>
 * <ul>
 *   <li><b>合并单元格</b>：只有左上角有值。这里把合并区清单保留下来，
 *       取值走 {@link PaExcelRaw.Sheet#valueAt}，避免「A 列每块只填第一行」被误判成缺失。</li>
 *   <li><b>数值格式</b>：权重写成 0.14 时 POI 给的是 double 0.14，直接 {@code String.valueOf}
 *       会带出 {@code 0.14000000000000001}。这里用 {@link BigDecimal#stripTrailingZeros()} 收敛，
 *       且整数不带小数点（1 而不是 1.0）—— 模型和后面的解析都省事。</li>
 *   <li><b>公式</b>：取缓存值（{@link CellType#FORMULA} 走 {@code getCachedFormulaResultType}），
 *       不重新计算。重新计算会触发整本 workbook 求值，慢且对不支持的函数会抛异常。</li>
 *   <li><b>空行</b>：整行全空的直接丢，否则模型看到几十行空数据会误判「表格到这就没了」。</li>
 * </ul>
 */
@Service
public class PaExcelReader {

    private static final Logger log = LoggerFactory.getLogger(PaExcelReader.class);

    /** 单个 sheet 最多读这么多行：防止有人传了个十万行的表把内存和模型都打死 */
    private static final int MAX_ROWS_PER_SHEET = 5000;

    /** 单格最大字符数：能力项定义/行为描述再长也不会超过这个量级 */
    private static final int MAX_CELL_CHARS = 4000;

    private final DataFormatter dataFormatter = new DataFormatter();

    /**
     * 读整本 workbook。
     *
     * @throws com.company.ability.exception.BusinessException 文件不是合法 xlsx/xls，或一个 sheet 都没有
     */
    public PaExcelRaw read(InputStream in, String fileName, long fileSize) {
        PaExcelRaw out = new PaExcelRaw();
        out.setFileName(fileName);
        out.setFileSize(fileSize);

        try (Workbook wb = WorkbookFactory.create(in)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                PaExcelRaw.Sheet target = readSheet(sheet);
                // 全空的 sheet（只有格式没有内容）不纳入：模型会把它们当成「待导入的岗位」
                if (!target.getRows().isEmpty()) {
                    out.getSheets().add(target);
                }
            }
        } catch (Exception e) {
            log.warn("读取 Excel 失败 fileName={}: {}", fileName, e.getMessage());
            throw new com.company.ability.exception.BusinessException(
                    "这个文件读不出来（" + e.getMessage() + "）。请确认它是 .xlsx 或 .xls，且没有加密。");
        }
        if (out.getSheets().isEmpty()) {
            throw new com.company.ability.exception.BusinessException("这个文件里没有任何内容，检查一下是不是空表。");
        }
        log.info("读取 Excel 完成 fileName={}, sheets={}, 行数={}",
                fileName, out.getSheets().size(),
                out.getSheets().stream().mapToInt(sh -> sh.getRows().size()).sum());
        return out;
    }

    private PaExcelRaw.Sheet readSheet(Sheet sheet) {
        PaExcelRaw.Sheet target = new PaExcelRaw.Sheet();
        target.setName(sheet.getSheetName());

        int colCount = 0;
        int maxRow = 0;
        for (Row row : sheet) {
            if (target.getRows().size() >= MAX_ROWS_PER_SHEET) {
                log.warn("sheet「{}」超过 {} 行，已截断", sheet.getSheetName(), MAX_ROWS_PER_SHEET);
                break;
            }
            int rowNum = row.getRowNum() + 1; // Excel 行号从 1 起
            java.util.Map<String, String> cells = new java.util.LinkedHashMap<>();
            boolean any = false;
            for (Cell cell : row) {
                String text = cellText(cell);
                if (text == null) {
                    continue;
                }
                cells.put(PaExcelRaw.Sheet.columnLetter(cell.getColumnIndex()), text);
                any = true;
                colCount = Math.max(colCount, cell.getColumnIndex() + 1);
            }
            if (any) {
                target.getRows().put(rowNum, cells);
                maxRow = Math.max(maxRow, rowNum);
            }
        }

        for (CellRangeAddress region : sheet.getMergedRegions()) {
            target.getMerges().add(region.formatAsString());
            colCount = Math.max(colCount, region.getLastColumn() + 1);
            maxRow = Math.max(maxRow, region.getLastRow() + 1);
        }

        target.setLastColumn(PaExcelRaw.Sheet.columnLetter(Math.max(colCount - 1, 0)));
        target.setLastRow(maxRow);
        return target;
    }

    /** 单元格 → 文本；空白返回 null。数值统一收敛成不带多余零的写法。 */
    private String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        String text;
        switch (type) {
            case STRING -> text = cell.getStringCellValue();
            case NUMERIC -> text = numericText(cell);
            case BOOLEAN -> text = String.valueOf(cell.getBooleanCellValue());
            case BLANK, ERROR, _NONE -> {
                return null;
            }
            default -> text = dataFormatter.formatCellValue(cell);
        }
        if (text == null) {
            return null;
        }
        text = text.replace("\r\n", "\n").trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_CELL_CHARS) {
            text = text.substring(0, MAX_CELL_CHARS);
        }
        return text;
    }

    /**
     * 数值单元格的文本化。
     *
     * <p>日期走 DataFormatter（用户配了日期格式就按格式显示）；其余用 BigDecimal 收掉浮点尾巴。
     * 注意这里<b>不做</b>「0.14 是 14% 还是 14 分」的判断 —— 那是解析层的事，
     * 读取层只保证「0.14 就是 0.14，不是 0.14000000000000001」。</p>
     */
    private String numericText(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e) {
                return dataFormatter.formatCellValue(cell);
            }
        }
        double v = cell.getNumericCellValue();
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
