package librarymanagement;

import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.swing.JFileChooser;
import javax.swing.JTable;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Exports whatever a JTable is showing (visible columns, filtered rows) to an .xlsx file.
 *
 * Example: ExcelExporter.export(this, tblOverdue, "DANH SÁCH QUÁ HẠN", "SachQuaHan");
 *
 * @author Felix
 */
public class ExcelExporter {

    public static void export(Component parent, JTable table, String title, String fileName) {
        if (table.getRowCount() == 0) {
            Utils.showWarning(parent, "Không có dữ liệu để xuất.");
            return;
        }

        // Ask where to save it
        String today = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Lưu file Excel");
        chooser.setFileFilter(new FileNameExtensionFilter("Excel (*.xlsx)", "xlsx"));
        chooser.setSelectedFile(new File(fileName + "_" + today + ".xlsx"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".xlsx")) {
            file = new File(file.getParentFile(), file.getName() + ".xlsx");
        }
        if (file.exists() && !Utils.confirm(parent, "File đã tồn tại. Ghi đè?")) {
            return;
        }

        // Build the workbook
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Data");

        Font boldFont = workbook.createFont();
        boldFont.setBold(true);

        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(boldFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Layout: title on row 0, export time on row 1, headers on row 3, data from row 4 down
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        sheet.createRow(1).createCell(0).setCellValue("Ngày xuất: " + now + "   -   Tổng số: " + table.getRowCount());

        Row headerRow = sheet.createRow(3);
        for (int col = 0; col < table.getColumnCount(); col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellValue(table.getColumnName(col));
            cell.setCellStyle(headerStyle);
        }

        for (int row = 0; row < table.getRowCount(); row++) {
            Row excelRow = sheet.createRow(row + 4);
            for (int col = 0; col < table.getColumnCount(); col++) {
                Object value = table.getValueAt(row, col);
                Cell cell = excelRow.createCell(col);
                if (value == null) {
                    cell.setCellValue("");
                } else if (value instanceof Number) {
                    cell.setCellValue(((Number) value).doubleValue());
                } else if (value instanceof java.sql.Date) {
                    cell.setCellValue(Utils.formatDate((java.sql.Date) value));
                } else {
                    cell.setCellValue(value.toString());
                }
            }
        }

        for (int col = 0; col < table.getColumnCount(); col++) {
            sheet.autoSizeColumn(col);
        }

        // Write it to disk
        try (FileOutputStream out = new FileOutputStream(file)) {
            workbook.write(out);
            workbook.close();
        } catch (IOException e) {
            Utils.showWarning(parent, "Không ghi được file (file có đang mở trong Excel không?).\n" + e.getMessage());
            return;
        }

        // Ask if they want to open it right away
        if (Utils.confirm(parent, "Đã xuất " + table.getRowCount() + " dòng ra file:\n" + file.getAbsolutePath()
                + "\n\nMở file ngay?")) {
            try {
                Desktop.getDesktop().open(file);
            } catch (IOException | UnsupportedOperationException e) {
                Utils.showWarning(parent, "Không mở được file: " + e.getMessage());
            }
        }
    }
}
