package librarymanagement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * All the borrowing rules live here: borrow, return, renew, fines and reservations.
 * The screens just call these methods.
 *
 * Anything that touches several tables runs inside a transaction:
 *   setAutoCommit(false), do the work, then commit() so everything gets saved,
 *   or rollback() if something fails, so nothing is left half-saved.
 *
 * @author Felix
 */
public class LoanService {

    /** Thrown when a rule is broken (expired card, book not available...). The message goes straight to the user. */
    public static class LoanException extends Exception {
        public LoanException(String message) {
            super(message);
        }
    }

    /**
     * Lends the given copies to a reader.
     * Returns the loan code printed on the slip, e.g. PM20261117-003.
     */
    public static String borrow(int readerId, List<Integer> copyIds, int accountId) throws SQLException, LoanException {
        if (copyIds.isEmpty()) {
            throw new LoanException("Chưa có sách nào trong phiếu mượn.");
        }
        Connection c = DBConnection.getConn();
        try {
            c.setAutoCommit(false);

            // Check the reader first: card, fines, overdue books, how many they already have
            int loanDays = checkReaderCanBorrow(c, readerId, copyIds.size());

            // Check each copy and lock its row so nobody else can lend it at the same moment
            for (int copyId : copyIds) {
                checkCopyCanBeLent(c, copyId, readerId);
            }

            // Create the loan slip
            String loanCode = makeLoanCode(c);
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO loans (loan_code, reader_id, created_by) VALUES (?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, loanCode);
            ps.setInt(2, readerId);
            ps.setInt(3, accountId);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            int loanId = keys.getInt(1);

            // One row per copy, and mark each copy as ON_LOAN
            LocalDate dueDate = LocalDate.now().plusDays(loanDays);
            for (int copyId : copyIds) {
                PreparedStatement item = c.prepareStatement(
                        "INSERT INTO loan_items (loan_id, copy_id, due_date) VALUES (?, ?, ?)");
                item.setInt(1, loanId);
                item.setInt(2, copyId);
                item.setObject(3, dueDate);
                item.executeUpdate();

                PreparedStatement upd = c.prepareStatement(
                        "UPDATE book_copies SET status = 'ON_LOAN' WHERE copy_id = ?");
                upd.setInt(1, copyId);
                upd.executeUpdate();

                // If this copy was being held for this reader, their reservation is done
                PreparedStatement res = c.prepareStatement(
                        "UPDATE reservations SET status = 'FULFILLED' "
                        + "WHERE held_copy_id = ? AND reader_id = ? AND status = 'READY'");
                res.setInt(1, copyId);
                res.setInt(2, readerId);
                res.executeUpdate();
            }

            c.commit();
            return loanCode;
        } catch (SQLException | LoanException e) {
            c.rollback();
            throw e;
        } finally {
            c.close();
        }
    }

    /**
     * Checks every rule for the reader and throws LoanException with the reason if they can't borrow.
     * Returns how many days this type of reader can keep a book.
     */
    public static int checkReaderCanBorrow(Connection c, int readerId, int newBooks) throws SQLException, LoanException {
        PreparedStatement ps = c.prepareStatement(
                "SELECT r.full_name, r.status, r.card_expiry, t.max_books, t.loan_days "
                + "FROM readers r JOIN reader_types t ON t.type_id = r.type_id WHERE r.reader_id = ?");
        ps.setInt(1, readerId);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            throw new LoanException("Không tìm thấy độc giả.");
        }
        String status = rs.getString("status");
        LocalDate expiry = rs.getDate("card_expiry").toLocalDate();
        int maxBooks = rs.getInt("max_books");
        int loanDays = rs.getInt("loan_days");

        // The card has to be active
        if (!status.equals("ACTIVE")) {
            throw new LoanException("Thẻ của độc giả đang không hoạt động (trạng thái: " + status + ").");
        }
        // and not expired
        if (expiry.isBefore(LocalDate.now())) {
            throw new LoanException("Thẻ đã hết hạn từ ngày " + Utils.formatDate(expiry) + ". Không thể mượn sách.");
        }
        // Unpaid fines can't be over the limit
        long debt = getUnpaidFines(c, readerId);
        int limit = Utils.getSettingInt("FINE_LOCK_THRESHOLD", 50000);
        if (debt > limit) {
            throw new LoanException("Độc giả đang nợ " + Utils.formatMoney(debt) + " tiền phạt (vượt mức "
                    + Utils.formatMoney(limit) + ").\nQuyền mượn đã bị khóa. Phải nộp phạt trước khi mượn.");
        }
        // No overdue books still at home
        ps = c.prepareStatement(
                "SELECT COUNT(*) FROM loan_items li JOIN loans l ON l.loan_id = li.loan_id "
                + "WHERE l.reader_id = ? AND li.return_date IS NULL AND li.due_date < CURDATE()");
        ps.setInt(1, readerId);
        rs = ps.executeQuery();
        rs.next();
        if (rs.getInt(1) > 0) {
            throw new LoanException("Độc giả đang giữ " + rs.getInt(1) + " sách quá hạn. Phải trả sách trước khi mượn tiếp.");
        }
        // And no more than max_books at once
        int onLoan = countBooksOnLoan(c, readerId);
        if (onLoan + newBooks > maxBooks) {
            throw new LoanException("Độc giả đang mượn " + onLoan + " cuốn, chỉ được mượn tối đa " + maxBooks
                    + " cuốn.\nCó thể mượn thêm " + Math.max(0, maxBooks - onLoan) + " cuốn.");
        }
        return loanDays;
    }

    /** Checks one copy. FOR UPDATE locks the row until we commit, so two librarians can't lend the same copy. */
    private static void checkCopyCanBeLent(Connection c, int copyId, int readerId) throws SQLException, LoanException {
        PreparedStatement ps = c.prepareStatement(
                "SELECT bc.barcode, bc.status, b.title, b.is_reference "
                + "FROM book_copies bc JOIN books b ON b.book_id = bc.book_id WHERE bc.copy_id = ? FOR UPDATE");
        ps.setInt(1, copyId);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            throw new LoanException("Không tìm thấy bản sách.");
        }
        String barcode = rs.getString("barcode");
        String status = rs.getString("status");
        String title = rs.getString("title");

        if (rs.getBoolean("is_reference")) {
            throw new LoanException("\"" + title + "\" là sách tham khảo, chỉ đọc tại chỗ.");
        }
        if (status.equals("AVAILABLE")) {
            return;
        }
        if (status.equals("ON_HOLD")) {
            // It's on hold for a reservation, so only the person who reserved it can take it
            PreparedStatement res = c.prepareStatement(
                    "SELECT reader_id FROM reservations WHERE held_copy_id = ? AND status = 'READY'");
            res.setInt(1, copyId);
            ResultSet rr = res.executeQuery();
            if (rr.next() && rr.getInt(1) == readerId) {
                return;
            }
            throw new LoanException("Bản sách " + barcode + " (\"" + title + "\") đang được giữ cho độc giả khác đã đặt trước.");
        }
        if (status.equals("ON_LOAN")) {
            throw new LoanException("Bản sách " + barcode + " (\"" + title + "\") đang được mượn, chưa trả.");
        }
        throw new LoanException("Bản sách " + barcode + " (\"" + title + "\") không thể cho mượn (trạng thái: " + status + ").");
    }

    /** The loan code is PM + today's date + a running number, e.g. PM20261117-003. */
    private static String makeLoanCode(Connection c) throws SQLException {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM loans WHERE loan_code LIKE ?");
        ps.setString(1, "PM" + today + "-%");
        ResultSet rs = ps.executeQuery();
        rs.next();
        int number = rs.getInt(1) + 1;
        return "PM" + today + "-" + String.format("%03d", number);
    }

    /**
     * Handles returning one borrowed copy.
     * condition is "GOOD", "DAMAGED" or "LOST".
     * Returns a message for the librarian (fines, reservation holds...).
     */
    public static String returnBook(int loanItemId, String condition, int accountId) throws SQLException, LoanException {
        Connection c = DBConnection.getConn();
        try {
            c.setAutoCommit(false);

            PreparedStatement ps = c.prepareStatement(
                    "SELECT li.copy_id, li.due_date, li.return_date, l.reader_id, bc.book_id, bc.barcode, b.title, b.price "
                    + "FROM loan_items li "
                    + "JOIN loans l ON l.loan_id = li.loan_id "
                    + "JOIN book_copies bc ON bc.copy_id = li.copy_id "
                    + "JOIN books b ON b.book_id = bc.book_id "
                    + "WHERE li.loan_item_id = ? FOR UPDATE");
            ps.setInt(1, loanItemId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                throw new LoanException("Không tìm thấy lượt mượn.");
            }
            if (rs.getTimestamp("return_date") != null) {
                throw new LoanException("Sách này đã được trả rồi.");
            }
            int copyId = rs.getInt("copy_id");
            int readerId = rs.getInt("reader_id");
            int bookId = rs.getInt("book_id");
            String title = rs.getString("title");
            long price = rs.getLong("price");
            LocalDate dueDate = rs.getDate("due_date").toLocalDate();

            String message = "Đã trả sách \"" + title + "\".";

            // Record the return
            ps = c.prepareStatement(
                    "UPDATE loan_items SET return_date = NOW(), returned_to = ?, return_condition = ? WHERE loan_item_id = ?");
            ps.setInt(1, accountId);
            ps.setString(2, condition);
            ps.setInt(3, loanItemId);
            ps.executeUpdate();

            // Fine for returning late
            long daysLate = ChronoUnit.DAYS.between(dueDate, LocalDate.now());
            if (daysLate > 0) {
                long fine = calculateOverdueFine(c, (int) daysLate);
                // but never more than a set percentage of the book's price
                int capPercent = Utils.getSettingInt("FINE_CAP_PERCENT", 100);
                if (capPercent > 0 && price > 0 && fine > price * capPercent / 100) {
                    fine = price * capPercent / 100;
                }
                addFine(c, readerId, loanItemId, "OVERDUE", (int) daysLate, fine, null);
                message += "\nTrả trễ " + daysLate + " ngày - phạt " + Utils.formatMoney(fine) + ".";
            }

            // Damaged or lost book
            String newStatus;
            if (condition.equals("LOST")) {
                addFine(c, readerId, loanItemId, "LOST", null, price, "Làm mất sách");
                message += "\nMất sách - bồi thường " + Utils.formatMoney(price) + ".";
                newStatus = "LOST";
            } else if (condition.equals("DAMAGED")) {
                long fine = price / 2;   // half the price
                addFine(c, readerId, loanItemId, "DAMAGED", null, fine, "Làm hư hỏng sách");
                message += "\nSách hư hỏng - phạt " + Utils.formatMoney(fine) + ".";
                newStatus = "DAMAGED";
            } else {
                newStatus = "AVAILABLE";
            }

            // If someone is waiting for this title, keep the copy for them
            if (newStatus.equals("AVAILABLE")) {
                String holdFor = holdForNextReservation(c, copyId, bookId);
                if (holdFor != null) {
                    newStatus = "ON_HOLD";
                    message += "\n\nCó người đặt trước! Hãy để sách lên kệ giữ sách cho: " + holdFor + ".";
                }
            }
            ps = c.prepareStatement("UPDATE book_copies SET status = ? WHERE copy_id = ?");
            ps.setString(1, newStatus);
            ps.setInt(2, copyId);
            ps.executeUpdate();

            c.commit();
            return message;
        } catch (SQLException | LoanException e) {
            c.rollback();
            throw e;
        } finally {
            c.close();
        }
    }

    /**
     * Works out the late fine from the tiers in fine_tiers, so the daily rate goes up the later it gets.
     * With 1,000/day for days 1-7, 2,000/day for days 8-30 and 5,000/day after that,
     * being 10 days late costs 7 x 1,000 + 3 x 2,000 = 13,000.
     */
    public static long calculateOverdueFine(Connection c, int daysLate) throws SQLException {
        long total = 0;
        PreparedStatement ps = c.prepareStatement(
                "SELECT from_day, to_day, amount_per_day FROM fine_tiers ORDER BY from_day");
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            int fromDay = rs.getInt("from_day");
            int toDay = rs.getInt("to_day");
            if (rs.wasNull()) {
                toDay = daysLate;          // the last tier is open-ended
            }
            long perDay = rs.getLong("amount_per_day");

            int lastDayInTier = Math.min(daysLate, toDay);
            if (lastDayInTier >= fromDay) {
                total += (lastDayInTier - fromDay + 1) * perDay;
            }
        }
        return total;
    }

    private static void addFine(Connection c, int readerId, int loanItemId, String type, Integer days, long amount,
                                String note) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fines (reader_id, loan_item_id, fine_type, days_overdue, amount, note) VALUES (?, ?, ?, ?, ?, ?)");
        ps.setInt(1, readerId);
        ps.setInt(2, loanItemId);
        ps.setString(3, type);
        ps.setObject(4, days);
        ps.setLong(5, amount);
        ps.setString(6, note);
        ps.executeUpdate();
    }

    /** Pushes the due date back by one loan period and returns the new date. */
    public static LocalDate renew(int loanItemId) throws SQLException, LoanException {
        Connection c = DBConnection.getConn();
        try {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT li.due_date, li.renew_count, li.return_date, t.max_renewals, t.loan_days, bc.book_id "
                    + "FROM loan_items li "
                    + "JOIN loans l ON l.loan_id = li.loan_id "
                    + "JOIN readers r ON r.reader_id = l.reader_id "
                    + "JOIN reader_types t ON t.type_id = r.type_id "
                    + "JOIN book_copies bc ON bc.copy_id = li.copy_id "
                    + "WHERE li.loan_item_id = ?");
            ps.setInt(1, loanItemId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next() || rs.getTimestamp("return_date") != null) {
                throw new LoanException("Lượt mượn không hợp lệ hoặc sách đã được trả.");
            }
            LocalDate dueDate = rs.getDate("due_date").toLocalDate();
            int renewCount = rs.getInt("renew_count");
            int maxRenewals = rs.getInt("max_renewals");
            int loanDays = rs.getInt("loan_days");
            int bookId = rs.getInt("book_id");

            if (dueDate.isBefore(LocalDate.now())) {
                throw new LoanException("Sách đã quá hạn, không thể gia hạn. Hãy trả sách và nộp phạt.");
            }
            if (renewCount >= maxRenewals) {
                throw new LoanException("Đã gia hạn " + renewCount + " lần, tối đa " + maxRenewals + " lần.");
            }
            ps = c.prepareStatement("SELECT COUNT(*) FROM reservations WHERE book_id = ? AND status = 'WAITING'");
            ps.setInt(1, bookId);
            rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) {
                throw new LoanException("Đầu sách này đang có người đặt trước, không thể gia hạn.");
            }

            LocalDate newDueDate = dueDate.plusDays(loanDays);
            ps = c.prepareStatement("UPDATE loan_items SET due_date = ?, renew_count = renew_count + 1 WHERE loan_item_id = ?");
            ps.setObject(1, newDueDate);
            ps.setInt(2, loanItemId);
            ps.executeUpdate();
            return newDueDate;
        } finally {
            c.close();
        }
    }

    /** Reserves a title for a reader. Only allowed when there's no copy left on the shelf. */
    public static void reserve(int readerId, int bookId) throws SQLException, LoanException {
        Connection c = DBConnection.getConn();
        try {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM book_copies WHERE book_id = ? AND status = 'AVAILABLE'");
            ps.setInt(1, bookId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) {
                throw new LoanException("Đầu sách này vẫn còn " + rs.getInt(1) + " bản trên kệ, có thể mượn ngay, không cần đặt trước.");
            }
            ps = c.prepareStatement("SELECT status, card_expiry FROM readers WHERE reader_id = ?");
            ps.setInt(1, readerId);
            rs = ps.executeQuery();
            if (!rs.next()) {
                throw new LoanException("Không tìm thấy độc giả.");
            }
            if (!rs.getString("status").equals("ACTIVE") || rs.getDate("card_expiry").toLocalDate().isBefore(LocalDate.now())) {
                throw new LoanException("Thẻ độc giả đã hết hạn hoặc bị khóa.");
            }
            ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM reservations WHERE reader_id = ? AND book_id = ? AND status IN ('WAITING', 'READY')");
            ps.setInt(1, readerId);
            ps.setInt(2, bookId);
            rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) {
                throw new LoanException("Độc giả đã đặt trước đầu sách này rồi.");
            }
            ps = c.prepareStatement("INSERT INTO reservations (reader_id, book_id) VALUES (?, ?)");
            ps.setInt(1, readerId);
            ps.setInt(2, bookId);
            ps.executeUpdate();
        } finally {
            c.close();
        }
    }

    /**
     * Called when a copy comes back. If anyone is WAITING for the title, the oldest reservation
     * becomes READY and the copy is kept aside for that reader.
     * Returns the reader's name, or null if nobody's waiting.
     */
    private static String holdForNextReservation(Connection c, int copyId, int bookId) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
                "SELECT rv.reservation_id, r.full_name, r.card_no FROM reservations rv "
                + "JOIN readers r ON r.reader_id = rv.reader_id "
                + "WHERE rv.book_id = ? AND rv.status = 'WAITING' ORDER BY rv.created_at LIMIT 1 FOR UPDATE");
        ps.setInt(1, bookId);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            return null;
        }
        int holdDays = Utils.getSettingInt("HOLD_DAYS", 3);
        PreparedStatement upd = c.prepareStatement(
                "UPDATE reservations SET status = 'READY', held_copy_id = ?, ready_at = NOW(), "
                + "expires_at = DATE_ADD(NOW(), INTERVAL ? DAY) WHERE reservation_id = ?");
        upd.setInt(1, copyId);
        upd.setInt(2, holdDays);
        upd.setInt(3, rs.getInt("reservation_id"));
        upd.executeUpdate();
        return rs.getString("full_name") + " (" + rs.getString("card_no") + "), giữ trong " + holdDays + " ngày";
    }

    /**
     * READY reservations that weren't picked up in time become EXPIRED,
     * and the copy moves on to the next person waiting (or back to the shelf).
     * Returns how many expired.
     */
    public static int expireOldHolds() throws SQLException {
        Connection c = DBConnection.getConn();
        int count = 0;
        try {
            c.setAutoCommit(false);
            PreparedStatement ps = c.prepareStatement(
                    "SELECT rv.reservation_id, rv.held_copy_id, rv.book_id FROM reservations rv "
                    + "WHERE rv.status = 'READY' AND rv.expires_at < NOW() FOR UPDATE");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int copyId = rs.getInt("held_copy_id");
                PreparedStatement upd = c.prepareStatement(
                        "UPDATE reservations SET status = 'EXPIRED' WHERE reservation_id = ?");
                upd.setInt(1, rs.getInt("reservation_id"));
                upd.executeUpdate();

                String nextReader = holdForNextReservation(c, copyId, rs.getInt("book_id"));
                upd = c.prepareStatement("UPDATE book_copies SET status = ? WHERE copy_id = ?");
                upd.setString(1, nextReader == null ? "AVAILABLE" : "ON_HOLD");
                upd.setInt(2, copyId);
                upd.executeUpdate();
                count++;
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.close();
        }
        return count;
    }

    public static long getUnpaidFines(Connection c, int readerId) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM fines WHERE reader_id = ? AND status = 'UNPAID'");
        ps.setInt(1, readerId);
        ResultSet rs = ps.executeQuery();
        rs.next();
        return rs.getLong(1);
    }

    public static int countBooksOnLoan(Connection c, int readerId) throws SQLException {
        PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM loan_items li JOIN loans l ON l.loan_id = li.loan_id "
                + "WHERE l.reader_id = ? AND li.return_date IS NULL");
        ps.setInt(1, readerId);
        ResultSet rs = ps.executeQuery();
        rs.next();
        return rs.getInt(1);
    }
}
