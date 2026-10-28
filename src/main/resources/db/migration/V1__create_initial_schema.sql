-- Flyway migration: src/main/resources/db/migration/V1__init.sql
-- MySQL 8, InnoDB, utf8mb4

CREATE TABLE users (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,            -- BCrypt
    full_name      VARCHAR(100) NOT NULL,
    role           ENUM('TEACHER','LIBRARIAN') NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Book categories (e.g. Matematika, Fisika). `parent_id` allows optional nesting.
CREATE TABLE book_categories (
    id         INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    parent_id  INT UNSIGNED NULL,
    CONSTRAINT fk_book_categories_parent FOREIGN KEY (parent_id) REFERENCES book_categories(id) ON DELETE SET NULL,
    UNIQUE KEY uq_book_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Archive categories (albums/events, e.g. Kegiatan Sekolah). `parent_id` allows optional nesting
-- (e.g. Kegiatan Sekolah > Upacara 2026).
CREATE TABLE archive_categories (
    id         INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    parent_id  INT UNSIGNED NULL,
    CONSTRAINT fk_archive_categories_parent FOREIGN KEY (parent_id) REFERENCES archive_categories(id) ON DELETE SET NULL,
    UNIQUE KEY uq_archive_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- BOOKS: ebooks, teaching modules, question banks, slides, etc.
-- ---------------------------------------------------------------------------
CREATE TABLE books (
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    author           VARCHAR(255) NULL,
    publisher        VARCHAR(255) NULL,
    publication_year SMALLINT     NULL,
    description      TEXT         NULL,

    book_type        ENUM('EBOOK','TEACHING_MODULE','QUESTION_BANK','SLIDES','OTHER')
                     NOT NULL DEFAULT 'EBOOK',
    category_id      INT UNSIGNED NULL,               -- references book_categories
    grade_level      ENUM('X','XI','XII','ALL') NOT NULL DEFAULT 'ALL',
    curriculum       VARCHAR(50)  NULL,               -- e.g. 'K13', 'Merdeka'

    -- PUBLIC: anyone on the Wi-Fi. STAFF: only logged-in teachers/librarians.
    visibility       ENUM('PUBLIC','STAFF') NOT NULL DEFAULT 'PUBLIC',

    -- file info (stored_path is relative to app.storage.root)
    original_name    VARCHAR(255) NOT NULL,
    stored_path      VARCHAR(500) NOT NULL UNIQUE,
    mime_type        VARCHAR(100) NOT NULL,
    file_size        BIGINT UNSIGNED NOT NULL,
    checksum_sha256  CHAR(64)     NOT NULL,
    cover_path       VARCHAR(500) NULL,

    download_count   INT UNSIGNED NOT NULL DEFAULT 0,
    uploaded_by      BIGINT UNSIGNED NULL,            -- NULL for bulk imports
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP    NULL,               -- soft delete

    CONSTRAINT fk_books_category FOREIGN KEY (category_id) REFERENCES book_categories(id) ON DELETE SET NULL,
    CONSTRAINT fk_books_user     FOREIGN KEY (uploaded_by) REFERENCES users(id)      ON DELETE SET NULL,
    UNIQUE KEY uq_books_checksum (checksum_sha256),
    KEY idx_books_type     (book_type),
    KEY idx_books_category (category_id),
    KEY idx_books_grade    (grade_level),
    KEY idx_books_created  (created_at),
    FULLTEXT KEY ft_books_search (title, author, description)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- ARCHIVE: school archive of images, videos and documents
-- ---------------------------------------------------------------------------
CREATE TABLE archive_items (
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    description      TEXT         NULL,
    media_type       ENUM('IMAGE','VIDEO','DOCUMENT') NOT NULL,
    category_id      INT UNSIGNED NULL,               -- references archive_categories
    event_date       DATE         NULL,               -- when the photo/video/event happened

    -- Default STAFF: archive is internal unless a librarian/teacher makes it PUBLIC.
    visibility       ENUM('PUBLIC','STAFF') NOT NULL DEFAULT 'STAFF',

    original_name    VARCHAR(255) NOT NULL,
    stored_path      VARCHAR(500) NOT NULL UNIQUE,
    mime_type        VARCHAR(100) NOT NULL,
    file_size        BIGINT UNSIGNED NOT NULL,
    checksum_sha256  CHAR(64)     NOT NULL,

    -- derived media info (nullable: filled when it can be extracted)
    thumbnail_path   VARCHAR(500) NULL,               -- generated for images and videos
    width            INT UNSIGNED NULL,
    height           INT UNSIGNED NULL,
    duration_seconds INT UNSIGNED NULL,               -- videos only

    view_count       INT UNSIGNED NOT NULL DEFAULT 0,
    uploaded_by      BIGINT UNSIGNED NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP    NULL,

    CONSTRAINT fk_archive_category FOREIGN KEY (category_id) REFERENCES archive_categories(id) ON DELETE SET NULL,
    CONSTRAINT fk_archive_user     FOREIGN KEY (uploaded_by) REFERENCES users(id)      ON DELETE SET NULL,
    UNIQUE KEY uq_archive_checksum (checksum_sha256),
    KEY idx_archive_media_type (media_type),
    KEY idx_archive_category   (category_id),
    KEY idx_archive_event_date (event_date),
    KEY idx_archive_created    (created_at),
    FULLTEXT KEY ft_archive_search (title, description)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- Stats and imports
-- ---------------------------------------------------------------------------

-- One row per download/view. Polymorphic (item_type + item_id), so no FK;
-- the app removes related rows when an item is permanently deleted.
-- No IP stored (anonymous users).
CREATE TABLE download_logs (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    item_type      ENUM('BOOK','ARCHIVE') NOT NULL,
    item_id        BIGINT UNSIGNED NOT NULL,
    downloaded_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_dl_item (item_type, item_id, downloaded_at),
    KEY idx_dl_time (downloaded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE import_jobs (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    target        ENUM('BOOK','ARCHIVE') NOT NULL,
    started_by    BIGINT UNSIGNED NULL,
    status        ENUM('RUNNING','COMPLETED','FAILED') NOT NULL DEFAULT 'RUNNING',
    total_rows    INT UNSIGNED NOT NULL DEFAULT 0,
    succeeded     INT UNSIGNED NOT NULL DEFAULT 0,
    skipped       INT UNSIGNED NOT NULL DEFAULT 0,    -- duplicates by checksum
    failed        INT UNSIGNED NOT NULL DEFAULT 0,
    error_report  MEDIUMTEXT NULL,                    -- per-row errors
    started_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at   TIMESTAMP NULL,
    CONSTRAINT fk_import_user FOREIGN KEY (started_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed book categories (edit freely)
INSERT INTO book_categories (name) VALUES
 ('Matematika'),('Bahasa Indonesia'),('Bahasa Inggris'),('Fisika'),
 ('Kimia'),('Biologi'),('Sejarah'),('Geografi'),
 ('Ekonomi'),('Sosiologi'),('Informatika'),('Umum');

-- Seed archive categories
INSERT INTO archive_categories (name) VALUES
 ('Kegiatan Sekolah'),('Dokumen Resmi'),('Prestasi'),('Lainnya');

-- The first LIBRARIAN account is created by the app on first start
-- (from env vars ADMIN_USERNAME / ADMIN_PASSWORD) so no password is committed here.