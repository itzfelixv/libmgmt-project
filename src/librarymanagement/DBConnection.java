/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package librarymanagement;

import java.sql.*;
import java.util.Properties;
import java.util.Vector;
import javax.swing.table.DefaultTableModel;
/**
 *
 * @author Felix
 */
public class DBConnection {
    private final String url = "jdbc:mysql://localhost:3306/libdb";
    private final String user = "root";
    private final String pass = "";

    
    public Connection getConn() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }
    
    public boolean testConn() {
        try (Connection c = getConn()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }
    
   private static PreparedStatement prepare(Connection c, String sql, boolean returnKeys,
                                             Object... params) throws SQLException {
        PreparedStatement ps = returnKeys
                ? c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                : c.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps;
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }
   
    public DefaultTableModel query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, false, params); ResultSet rs = ps.executeQuery()) {

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            Vector<String> headers = new Vector<>();
            Class<?>[] types = new Class<?>[cols];
            for (int i = 1; i <= cols; i++) {
                headers.add(md.getColumnLabel(i));
                try {
                    types[i - 1] = Class.forName(md.getColumnClassName(i));
                } catch (ClassNotFoundException e) {
                    types[i - 1] = Object.class;
                }
            }

            Vector<Vector<Object>> data = new Vector<>();
            while (rs.next()) {
                Vector<Object> row = new Vector<>(cols);
                for (int i = 1; i <= cols; i++) row.add(rs.getObject(i));
                data.add(row);
            }

            return new DefaultTableModel(data, headers) {
                @Override public boolean isCellEditable(int r, int col) { return false; }
                @Override public Class<?> getColumnClass(int col) { return types[col]; }
            };
        }
    }
}
