DROP DATABASE IF EXISTS library_db;
CREATE DATABASE library_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE library_db;

-- accounts & system
CREATE TABLE accounts (
    account_id     INT AUTO_INCREMENT PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL UNIQUE,
    password_hash  CHAR(60)     NOT NULL,
    full_name      VARCHAR(100) NOT NULL,
    email          VARCHAR(100),
    phone          CHAR(10),
    role           ENUM('ADMIN','LIBRARIAN') NOT NULL DEFAULT 'LIBRARIAN',
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_pw BOOLEAN NOT NULL DEFAULT FALSE,
    last_login     DATETIME,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE settings (
    setting_key   VARCHAR(50) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    description   VARCHAR(255)
);

CREATE TABLE fine_tiers (
    tier_id        INT AUTO_INCREMENT PRIMARY KEY,
    from_day       INT NOT NULL,
    to_day         INT NULL,
    amount_per_day DECIMAL(10,0) NOT NULL,
    CHECK (from_day >= 1),
    CHECK (to_day IS NULL OR to_day >= from_day),
    CHECK (amount_per_day >= 0)
);

CREATE TABLE activity_logs (
    log_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id INT NULL,
    action     VARCHAR(30) NOT NULL,
    table_name VARCHAR(50),
    record_id  VARCHAR(50),
    details    TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE SET NULL,
    INDEX idx_log_time (created_at)
);

-- faculties, cohorts, classes
CREATE TABLE faculties (
    faculty_id INT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(20)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL
);

CREATE TABLE cohorts (
    cohort_id  INT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(20) NOT NULL UNIQUE,
    start_year SMALLINT NOT NULL,
    end_year   SMALLINT NOT NULL,
    CHECK (end_year > start_year)
);

CREATE TABLE classes (
    class_id   INT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(20) NOT NULL UNIQUE,
    name       VARCHAR(100),
    faculty_id INT NOT NULL,
    cohort_id  INT NOT NULL,
    FOREIGN KEY (faculty_id) REFERENCES faculties(faculty_id),
    FOREIGN KEY (cohort_id)  REFERENCES cohorts(cohort_id)
);

-- readers
CREATE TABLE reader_types (
    type_id      INT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(20) NOT NULL UNIQUE,
    name         VARCHAR(50) NOT NULL,
    max_books    INT NOT NULL,
    loan_days    INT NOT NULL,
    max_renewals INT NOT NULL DEFAULT 1,
    CHECK (max_books > 0 AND loan_days > 0 AND max_renewals >= 0)
);

CREATE TABLE readers (
    reader_id   INT AUTO_INCREMENT PRIMARY KEY,
    card_no     VARCHAR(20)  NOT NULL UNIQUE,
    type_id     INT NOT NULL,
    full_name   VARCHAR(100) NOT NULL,
    gender      ENUM('M','F','O'),
    dob         DATE,
    email       VARCHAR(100),
    phone       CHAR(10),
    faculty_id  INT NULL,
    class_id    INT NULL,
    photo_path  VARCHAR(255),
    card_issued DATE NOT NULL,
    card_expiry DATE NOT NULL,
    status      ENUM('ACTIVE','SUSPENDED','GRADUATED','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    note        VARCHAR(255),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (type_id)    REFERENCES reader_types(type_id),
    FOREIGN KEY (faculty_id) REFERENCES faculties(faculty_id),
    FOREIGN KEY (class_id)   REFERENCES classes(class_id),
    CHECK (card_expiry > card_issued),
    INDEX idx_reader_name (full_name)
);

-- catalogue
CREATE TABLE categories (
    category_id INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    parent_id   INT NULL,
    FOREIGN KEY (parent_id) REFERENCES categories(category_id)
);

CREATE TABLE publishers (
    publisher_id INT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(150) NOT NULL UNIQUE
);

CREATE TABLE authors (
    author_id INT AUTO_INCREMENT PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    INDEX idx_author_name (name)
);

CREATE TABLE books (
    book_id      INT AUTO_INCREMENT PRIMARY KEY,
    isbn         VARCHAR(13) NULL UNIQUE,
    title        VARCHAR(255) NOT NULL,
    category_id  INT NOT NULL,
    publisher_id INT NULL,
    publish_year SMALLINT,
    edition      VARCHAR(20),
    language     VARCHAR(30) DEFAULT 'Vietnamese',
    pages        INT,
    price        DECIMAL(12,0) NOT NULL DEFAULT 0,
    is_reference BOOLEAN NOT NULL DEFAULT FALSE,
    cover_path   VARCHAR(255),
    description  TEXT,
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (category_id)  REFERENCES categories(category_id),
    FOREIGN KEY (publisher_id) REFERENCES publishers(publisher_id),
    CHECK (price >= 0),
    FULLTEXT INDEX ft_title (title)
);

CREATE TABLE book_authors (
    book_id   INT NOT NULL,
    author_id INT NOT NULL,
    PRIMARY KEY (book_id, author_id),
    FOREIGN KEY (book_id)   REFERENCES books(book_id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES authors(author_id)
);

CREATE TABLE book_copies (
    copy_id       INT AUTO_INCREMENT PRIMARY KEY,
    book_id       INT NOT NULL,
    barcode       VARCHAR(20) NOT NULL UNIQUE,
    shelf_code    VARCHAR(30),
    status        ENUM('AVAILABLE','ON_LOAN','ON_HOLD','DAMAGED','LOST','WITHDRAWN')
                  NOT NULL DEFAULT 'AVAILABLE',
    acquired_date DATE,
    note          VARCHAR(255),
    FOREIGN KEY (book_id) REFERENCES books(book_id),
    INDEX idx_copy_book_status (book_id, status)
);

-- circulation
CREATE TABLE loans (
    loan_id    INT AUTO_INCREMENT PRIMARY KEY,
    loan_code  VARCHAR(20) NOT NULL UNIQUE,
    reader_id  INT NOT NULL,
    created_by INT NOT NULL,
    loan_date  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note       VARCHAR(255),
    FOREIGN KEY (reader_id)  REFERENCES readers(reader_id),
    FOREIGN KEY (created_by) REFERENCES accounts(account_id),
    INDEX idx_loan_reader (reader_id)
);

CREATE TABLE loan_items (
    loan_item_id     INT AUTO_INCREMENT PRIMARY KEY,
    loan_id          INT NOT NULL,
    copy_id          INT NOT NULL,
    due_date         DATE NOT NULL,
    renew_count      INT NOT NULL DEFAULT 0,
    return_date      DATETIME NULL,
    returned_to      INT NULL,
    return_condition ENUM('GOOD','DAMAGED','LOST') NULL,
    open_copy_id     INT AS (IF(return_date IS NULL, copy_id, NULL)) STORED,
    UNIQUE KEY uq_one_open_loan_per_copy (open_copy_id),
    FOREIGN KEY (loan_id)     REFERENCES loans(loan_id),
    FOREIGN KEY (copy_id)     REFERENCES book_copies(copy_id),
    FOREIGN KEY (returned_to) REFERENCES accounts(account_id),
    INDEX idx_due_open (return_date, due_date)
);

CREATE TABLE fines (
    fine_id      INT AUTO_INCREMENT PRIMARY KEY,
    reader_id    INT NOT NULL,
    loan_item_id INT NULL,
    fine_type    ENUM('OVERDUE','DAMAGED','LOST','OTHER') NOT NULL,
    days_overdue INT NULL,
    amount       DECIMAL(12,0) NOT NULL,
    status       ENUM('UNPAID','PAID','WAIVED') NOT NULL DEFAULT 'UNPAID',
    receipt_no   VARCHAR(20) UNIQUE,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at      DATETIME NULL,
    handled_by   INT NULL,
    note         VARCHAR(255),
    FOREIGN KEY (reader_id)    REFERENCES readers(reader_id),
    FOREIGN KEY (loan_item_id) REFERENCES loan_items(loan_item_id),
    FOREIGN KEY (handled_by)   REFERENCES accounts(account_id),
    CHECK (amount >= 0),
    INDEX idx_fine_reader_status (reader_id, status)
);

CREATE TABLE reservations (
    reservation_id INT AUTO_INCREMENT PRIMARY KEY,
    reader_id      INT NOT NULL,
    book_id        INT NOT NULL,
    status         ENUM('WAITING','READY','FULFILLED','CANCELLED','EXPIRED')
                   NOT NULL DEFAULT 'WAITING',
    held_copy_id   INT NULL,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at       DATETIME NULL,
    expires_at     DATETIME NULL,
    active_key     VARCHAR(30) AS (IF(status IN ('WAITING','READY'),
                                      CONCAT(reader_id,'-',book_id), NULL)) STORED,
    UNIQUE KEY uq_one_active_reservation (active_key),
    FOREIGN KEY (reader_id)    REFERENCES readers(reader_id),
    FOREIGN KEY (book_id)      REFERENCES books(book_id),
    FOREIGN KEY (held_copy_id) REFERENCES book_copies(copy_id),
    INDEX idx_resv_queue (book_id, status, created_at)
);

-- views
CREATE VIEW v_open_loans AS
SELECT li.loan_item_id, l.loan_code, r.reader_id, r.card_no, r.full_name,
       c.code AS class_code, f.name AS faculty_name,
       bc.barcode, b.title, l.loan_date, li.due_date,
       GREATEST(DATEDIFF(CURRENT_DATE, li.due_date), 0) AS days_overdue
FROM loan_items li
JOIN loans l        ON l.loan_id = li.loan_id
JOIN readers r      ON r.reader_id = l.reader_id
LEFT JOIN classes c   ON c.class_id = r.class_id
LEFT JOIN faculties f ON f.faculty_id = COALESCE(r.faculty_id, c.faculty_id)
JOIN book_copies bc ON bc.copy_id = li.copy_id
JOIN books b        ON b.book_id = bc.book_id
WHERE li.return_date IS NULL;

CREATE VIEW v_reader_debt AS
SELECT reader_id, SUM(amount) AS unpaid_total
FROM fines WHERE status = 'UNPAID'
GROUP BY reader_id;

-- seed data
INSERT INTO settings VALUES
 ('FINE_LOCK_THRESHOLD', '50000', 'Unpaid fines above this block borrowing (VND)'),
 ('HOLD_DAYS',           '3',     'Days a reservation is held for pickup'),
 ('OVERDUE_REPORT_DAYS', '30',    'Days overdue to include in the overdue report'),
 ('FINE_CAP_PERCENT',    '100',   'Max overdue fine per item as % of book price (0 = no cap)');

INSERT INTO fine_tiers (from_day, to_day, amount_per_day) VALUES
 (1, 7, 1000),
 (8, 30, 2000),
 (31, NULL, 5000);

INSERT INTO reader_types (code, name, max_books, loan_days, max_renewals) VALUES
 ('STUDENT',  'Sinh viên / Học sinh', 5, 14, 1),
 ('LECTURER', 'Giảng viên / Giáo viên', 10, 60, 2),
 ('STAFF',    'Cán bộ', 5, 30, 1);

-- default admin, password: admin123
INSERT INTO accounts (username, password_hash, full_name, role, must_change_pw) VALUES
 ('admin', '$2a$10$yHJwWus59hYkHxM3.UrVtOdzn.h2UPJy1QIoSJBL.5G0bKmo11MJy',
  'Administrator', 'ADMIN', TRUE);