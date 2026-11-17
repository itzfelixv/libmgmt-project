/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package librarymanagement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.security.auth.login.LoginException;

import org.mindrot.jbcrypt.BCrypt;
/**
 * Keeps track of who is logged in.
 * @author Felix
 */
public class Session {
    private int accountId = -1;
    private String username;
    private String fullName;
    private String role;          // "ADMIN" or "LIBRARIAN"
    private boolean mustChangePassword;
    
    public void login(String user, String password) throws SQLException, LoginException {
        if (user.isBlank() || password.isEmpty()) {
            throw new LoginException("Vui lòng nhập tên đăng nhập và mật khẩu.");
        }
        
        String sql = "SELECT account_id, username, password_hash, full_name, role, is_active, must_change_pw FROM accounts WHERE username = ?";
        try (Connection c = DBConnection.getConn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !BCrypt.checkpw(password, rs.getString("password_hash"))) {
                    throw new LoginException("Sai tên đăng nhập hoặc mật khẩu.");
                }
                if (!rs.getBoolean("is_active")) {
                    throw new LoginException("Tài khoản đã bị khóa.");
                }
                accountId = rs.getInt("account_id");
                username = rs.getString("username");
                fullName = rs.getString("full_name");
                role = rs.getString("role");
                mustChangePassword = rs.getBoolean("must_change_pw");
            }
        }
        DBConnection.update("UPDATE accounts SET last_login = NOW() WHERE account_id = ?", accountId);
    }
    
    public void changePassword(String oldPw, String newPw) throws SQLException, LoginException {
        Object hash = DBConnection.scalar("SELECT password_hash FROM accounts WHERE account_id = ?", accountId);
        if (hash == null || !BCrypt.checkpw(oldPw, hash.toString())) {
            throw new LoginException("Mật khẩu cũ không đúng.");
        }
        if (newPw.length() < 8) {
            throw new LoginException("Mật khẩu mới phải có ít nhất 8 ký tự.");
        }
        if (newPw.equals(oldPw)) {
            throw new LoginException("Mật khẩu mới phải khác mật khẩu cũ.");
        }
        DBConnection.update("UPDATE accounts SET password_hash = ?, must_change_pw = FALSE WHERE account_id = ?",
                BCrypt.hashpw(newPw, BCrypt.gensalt()), accountId);
        mustChangePassword = false;
    }
    
    public void logout() {
        accountId = -1;
        username = fullName = role = null;
        mustChangePassword = false;
    }
    
    public boolean isLoggedIn() { return accountId > 0; }
    public boolean isAdmin() { return "ADMIN".equals(role); }
    public int getAccountId() { return accountId; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public String getRole() { return role; }
    public boolean mustChangePassword(){ return mustChangePassword; }
}
