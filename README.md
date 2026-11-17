# Library Management

A desktop library management app for high school and college/university libraries.
It's built with Java Swing (designed in NetBeans), uses MySQL for the database, and is a NetBeans "Java with Ant" project.
The interface is in Vietnamese.

## Features

- **Login and roles**: admin and librarian accounts. Passwords are hashed with BCrypt, and users have to change the default password the first time they log in.
- **Readers**: students and lecturers with card number, class, faculty, cohort and card expiry date. Includes live search.
- **Books**: titles and their physical copies. Every copy has its own barcode (BC000001, ...).
- **Borrow and return**: works with a USB barcode scanner. Renewals, quick return by scanning only the book, and checks for expired cards, unavailable books, overdue books and borrowing limits.
- **Fines**: progressive overdue fines, plus charges for damaged or lost books. Borrowing is blocked automatically when unpaid fines go over the limit.
- **Reservations**: a reader can reserve a title that has no copies left. When a copy comes back, it's kept for the first person in line.
- **Reports**: books on loan, overdue books, and the list of students more than 30 days overdue, all exportable to Excel.
- **Catalog**: faculties, cohorts, classes, categories, publishers and authors.
- **Main window**: a clock in the status bar and keyboard shortcuts (F2 readers, F3 books, F5 borrow/return, Ctrl+L log out).

## Project structure

```
src/librarymanagement/
  LibraryManagement.java   entry point
  DBConnection.java        database connection and query helpers
  Session.java             login, logout, change password
  LoanService.java         borrowing rules: borrow, return, renew, fines, reservations
  Utils.java               shared helpers for tables, combo boxes, dates, messages
  ComboItem.java           combo box item that keeps a database id
  ExcelExporter.java       exports a table to .xlsx
  LoginFrame, MainFrame, ChangePwdDialog          windows
  ReaderPanel, BookPanel, CirculationPanel,       screens opened as tabs
  OverduePanel, FinePanel, ReservationPanel,
  CatalogPanel, AccountPanel
```

Each window or screen has a `.form` file, so you can edit its layout in NetBeans' Design tab.

## Requirements

- JDK 26
- Apache NetBeans
- MySQL 8 (with MySQL Workbench or any SQL client)
- These libraries, added to the project under **Project Properties → Libraries → Classpath**:
  - MySQL Connector/J 9.6.0
  - jBCrypt 0.4
  - Apache POI 6.0.0: the `poi-bin` download, using the main jars plus everything in its `lib/` and `ooxml-lib/` folders

## Setup

1. **Clone or copy the project** and open it in NetBeans (File → Open Project).

2. **Create the database.** Run the schema script `library_schema.sql` in MySQL Workbench.

3. **Check the connection settings** at the top of `src/librarymanagement/DBConnection.java`:
   ```java
   private static final String url = "jdbc:mysql://localhost:3306/libdb";
   private static final String user = "root";
   private static final String pass = "";
   ```
   Change the user and password to match your MySQL server.

4. **Add the libraries** listed above if NetBeans shows them as missing (Project Properties → Libraries).

5. **Run** with F6. The main class is `librarymanagement.LibraryManagement`, which opens the login window.

6. **Log in** with the default admin account:
   - Username: `admin`
   - Password: `admin123`

   You'll be asked to set a new password (at least 8 characters).

7. **Add the basic data first.** Under Quản lý → Danh mục, create at least one faculty, cohort, class and category.
   After that you can add readers and books.

