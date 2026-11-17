package librarymanagement;

/**
 * An item for a JComboBox.
 * It shows text to the user but also keeps the database id (or a code like "ACTIVE") behind the scenes.
 *
 * Example:
 *   cboType.addItem(new ComboItem(1, "STUDENT", "Sinh viên"));
 *   ComboItem item = (ComboItem) cboType.getSelectedItem();
 *   int typeId = item.getId();
 *
 * @author Felix
 */
public class ComboItem {

    private Integer id;      // database id, null for the "(Không)" item
    private String code;     // optional code like "ACTIVE"
    private String text;     // what the user sees

    public ComboItem(Integer id, String code, String text) {
        this.id = id;
        this.code = code;
        this.text = text;
    }

    public Integer getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    // JComboBox uses toString() for the text it displays
    @Override
    public String toString() {
        return text;
    }
}
