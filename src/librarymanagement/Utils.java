package librarymanagement;

import java.awt.Component;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

/**
 * Small helpers shared by all the screens.
 *
 * @author Felix
 */
public class Utils {

    // Dates are always shown and typed as dd/MM/yyyy
    public static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0");

    /**
     * Runs a SELECT and puts the result in the table.
     */
    public static void loadTable(JTable table, String sql, Object... params) throws SQLException {
        Connection c = DBConnection.getConn();
        try {
            DefaultTableModel model = new DBConnection().query(c, sql, params);
            table.setModel(model);
            table.setRowSorter(new TableRowSorter<TableModel>(model));
        } finally {
            c.close();
        }
        table.setDefaultRenderer(java.sql.Date.class, new DateRenderer());
        table.setDefaultRenderer(BigDecimal.class, new MoneyRenderer());
    }

    /** Hides a column from view (the data stays in the model). When hiding several, go from right to left. */
    public static void hideColumn(JTable table, int column) {
        table.removeColumn(table.getColumnModel().getColumn(column));
    }

    /** Value in the given model column of the selected row, or null if nothing's selected. */
    public static Object getSelectedValue(JTable table, int modelColumn) {
        int row = table.getSelectedRow();
        if (row == -1) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(row);
        return table.getModel().getValueAt(modelRow, modelColumn);
    }

    /** Id (column 0) of the selected row, or -1 if nothing's selected. */
    public static int getSelectedId(JTable table) {
        Object value = getSelectedValue(table, 0);
        if (value == null) {
            return -1;
        }
        return ((Number) value).intValue();
    }

    /** Selects the row whose column 0 matches the id. */
    public static void selectRowById(JTable table, int id) {
        for (int i = 0; i < table.getRowCount(); i++) {
            int modelRow = table.convertRowIndexToModel(i);
            Object value = table.getModel().getValueAt(modelRow, 0);
            if (value != null && ((Number) value).intValue() == id) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    /**
     * Live search: hides the rows that don't contain the text.
     */
    @SuppressWarnings("unchecked")
    public static void filterTable(JTable table, String text, int... modelColumns) {
        TableRowSorter<TableModel> sorter = (TableRowSorter<TableModel>) table.getRowSorter();
        if (sorter == null) {
            return;
        }
        text = text.trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text), modelColumns));
        }
    }

    /**
     * Fills a combo box from a SELECT that returns two columns: id and text.
     */
    public static void fillCombo(JComboBox<ComboItem> combo, String sql, String noneText) throws SQLException {
        combo.removeAllItems();
        if (noneText != null) {
            combo.addItem(new ComboItem(null, null, noneText));
        }
        Connection c = DBConnection.getConn();
        try {
            PreparedStatement ps = c.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                combo.addItem(new ComboItem(rs.getInt(1), null, rs.getString(2)));
            }
        } finally {
            c.close();
        }
    }

    /** Selects the item with this id (null means the "(Không)" item). */
    public static void selectComboId(JComboBox<ComboItem> combo, Integer id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            Integer itemId = combo.getItemAt(i).getId();
            if ((id == null && itemId == null) || (id != null && id.equals(itemId))) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Selects the item with this code, like "ACTIVE". */
    public static void selectComboCode(JComboBox<ComboItem> combo, String code) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (code != null && code.equals(combo.getItemAt(i).getCode())) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Id of the selected item, or null. */
    public static Integer getComboId(JComboBox<ComboItem> combo) {
        ComboItem item = (ComboItem) combo.getSelectedItem();
        if (item == null) {
            return null;
        }
        return item.getId();
    }

    /** Code of the selected item, or null. */
    public static String getComboCode(JComboBox<ComboItem> combo) {
        ComboItem item = (ComboItem) combo.getSelectedItem();
        if (item == null) {
            return null;
        }
        return item.getCode();
    }

    /** Turns "25/12/2026" into a LocalDate. Empty text gives null, bad text throws DateTimeParseException. */
    public static LocalDate parseDate(String text) {
        text = text.trim();
        if (text.isEmpty()) {
            return null;
        }
        return LocalDate.parse(text, DATE_FORMAT);
    }

    public static String formatDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.format(DATE_FORMAT);
    }

    public static String formatDate(java.sql.Date date) {
        if (date == null) {
            return "";
        }
        return formatDate(date.toLocalDate());
    }

    public static String formatMoney(long amount) {
        return MONEY_FORMAT.format(amount) + " đ";
    }

    public static int getSettingInt(String key, int defaultValue) {
        try {
            Object value = DBConnection.scalar("SELECT setting_value FROM settings WHERE setting_key = ?", key);
            if (value != null) {
                return Integer.parseInt(value.toString().trim());
            }
        } catch (SQLException | NumberFormatException e) {}
        return defaultValue;
    }

    public static void showInfo(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Thông báo", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void showWarning(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Chú ý", JOptionPane.WARNING_MESSAGE);
    }

    public static boolean confirm(Component parent, String message) {
        int answer = JOptionPane.showConfirmDialog(parent, message, "Xác nhận", JOptionPane.YES_NO_OPTION);
        return answer == JOptionPane.YES_OPTION;
    }

    /** Shows a database error in plain Vietnamese. */
    public static void showError(Component parent, SQLException ex) {
        String message;
        message = switch (ex.getErrorCode()) {
            case 1062 -> "Mã này đã tồn tại. Vui lòng nhập mã khác.";
            case 1451 -> "Không thể xóa vì dữ liệu này đang được sử dụng ở nơi khác.";
            default -> "Lỗi cơ sở dữ liệu:\n" + ex.getMessage();
        };
        JOptionPane.showMessageDialog(parent, message, "Lỗi", JOptionPane.ERROR_MESSAGE);
    }

    private static class DateRenderer extends DefaultTableCellRenderer {
        @Override
        protected void setValue(Object value) {
            if (value instanceof java.sql.Date date) {
                setText(formatDate(date));
            } else {
                setText(value == null ? "" : value.toString());
            }
        }
    }

    private static class MoneyRenderer extends DefaultTableCellRenderer {
        MoneyRenderer() {
            setHorizontalAlignment(RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            if (value instanceof BigDecimal) {
                setText(MONEY_FORMAT.format(value));
            } else {
                setText(value == null ? "" : value.toString());
            }
        }
    }
}
