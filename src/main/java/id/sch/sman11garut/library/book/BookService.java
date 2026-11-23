package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.category.BookCategory;
import id.sch.sman11garut.library.category.CategoryService;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.common.StorageUsage;
import id.sch.sman11garut.library.common.TrashItemDto;
import id.sch.sman11garut.library.common.Visibility;
import id.sch.sman11garut.library.config.TrashProperties;
import id.sch.sman11garut.library.stats.DownloadEvent;
import id.sch.sman11garut.library.stats.ItemType;
import id.sch.sman11garut.library.stats.ItemsPurgedEvent;
import id.sch.sman11garut.library.storage.FileStorageService;
import id.sch.sman11garut.library.storage.ServedFile;
import id.sch.sman11garut.library.storage.StorageCategory;
import id.sch.sman11garut.library.storage.StoredFileInfo;
import id.sch.sman11garut.library.user.Role;
import id.sch.sman11garut.library.user.User;
import id.sch.sman11garut.library.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BookService {

    private static final Logger log = LoggerFactory.getLogger(BookService.class);

    private static final String DEFAULT_COVER_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" viewBox="0 0 300 400">
              <rect width="300" height="400" fill="#2d3748"/>
              <text x="150" y="200" font-family="sans-serif" font-size="20" fill="#a0aec0" text-anchor="middle">No Cover</text>
            </svg>
            """;

    private final BookRepository bookRepository;
    private final CategoryService categoryService;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final TrashProperties trashProperties;

    public BookService(
            BookRepository bookRepository,
            CategoryService categoryService,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            ApplicationEventPublisher eventPublisher,
            TrashProperties trashProperties) {
        this.bookRepository = bookRepository;
        this.categoryService = categoryService;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.eventPublisher = eventPublisher;
        this.trashProperties = trashProperties;
    }

    @Transactional(readOnly = true)
    public PageResponse<BookSummaryDto> listBooks(BookSearchCriteria criteria, boolean isStaff, Pageable pageable) {
        Page<Book> pageResult;
        String query = criteria != null && criteria.q() != null ? criteria.q().trim() : null;

        if (query != null && query.length() >= 3) {
            Pageable nativePageable = resolveNativePageable(criteria, pageable);
            String gradeStr = criteria.grade() != null ? criteria.grade().name() : null;
            String typeStr = criteria.type() != null ? criteria.type().name() : null;
            pageResult = bookRepository.searchFulltext(
                    query,
                    criteria.categoryId(),
                    gradeStr,
                    typeStr,
                    isStaff,
                    nativePageable
            );
        } else {
            Pageable jpaPageable = resolveJpaPageable(criteria, pageable);
            pageResult = bookRepository.findAll(
                    BookSpecifications.withCriteria(criteria, isStaff),
                    jpaPageable
            );
        }

        return PageResponse.from(pageResult.map(BookSummaryDto::from));
    }

    @Transactional(readOnly = true)
    public BookDetailDto getBook(Long id, boolean isStaff) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        if (book.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Book not found with id: " + id);
        }

        return BookDetailDto.from(book);
    }

    // The open* methods only look up and check the item. The controller streams the result
    // after the transaction has ended, so a long download never holds a DB connection.

    @Transactional(readOnly = true)
    public ServedFile openFile(Long id, boolean isStaff) {
        Book book = findVisibleBook(id, isStaff);
        return new ServedFile(
                fileStorageService.loadAsResource(book.getStoredPath()), book.getMimeType(), book.getOriginalName());
    }

    /** Same as {@link #openFile}, and counts the download (asynchronously). */
    @Transactional(readOnly = true)
    public ServedFile openDownload(Long id, boolean isStaff) {
        ServedFile file = openFile(id, isStaff);
        eventPublisher.publishEvent(new DownloadEvent(ItemType.BOOK, id, Instant.now()));
        return file;
    }

    /** The cover with its real type, or the SVG placeholder. STAFF books stay hidden (404) from anonymous callers. */
    @Transactional(readOnly = true)
    public ServedFile openCover(Long id, boolean isStaff) {
        Book book = findVisibleBook(id, isStaff);
        if (book.getCoverPath() == null || book.getCoverPath().isBlank()) {
            return ServedFile.svg(DEFAULT_COVER_SVG);
        }
        try {
            return new ServedFile(
                    fileStorageService.loadAsResource(book.getCoverPath()),
                    ServedFile.imageTypeOf(book.getCoverPath()),
                    null);
        } catch (AppException ex) {
            // Missing cover file or drive unplugged: a placeholder keeps the catalog browsable.
            log.debug("Cover for book {} unavailable ({}), serving placeholder", id, ex.getCode());
            return ServedFile.svg(DEFAULT_COVER_SVG);
        }
    }

    private Book findVisibleBook(Long id, boolean isStaff) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));
        if (book.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Book not found with id: " + id);
        }
        return book;
    }

    @Transactional
    public BookDetailDto createBook(
            MultipartFile file,
            MultipartFile cover,
            BookMetadataRequest metadata,
            UserPrincipal principal) {

        if (file == null || file.isEmpty()) {
            throw AppException.badRequest("FILE_REQUIRED", "Book file is required");
        }

        StoredFileInfo storedFile;
        try {
            storedFile = storeUniqueBookFile(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException ex) {
            throw AppException.badRequest("STORAGE_FAILED", "Failed to stream uploaded book file");
        }

        StoredFileInfo storedCover = null;
        if (cover != null && !cover.isEmpty()) {
            try {
                storedCover = fileStorageService.storeFile(cover.getInputStream(), cover.getOriginalFilename(), StorageCategory.COVER);
            } catch (Exception ex) {
                fileStorageService.deleteStoredFile(storedFile.relativePath());
                throw AppException.badRequest("STORAGE_FAILED", "Failed to stream uploaded cover image: " + ex.getMessage());
            }
        }

        return persistNewBook(storedFile, storedCover, metadata, principal);
    }

    /**
     * Creates a book from a raw stream (used by bulk import). Goes through the same
     * storage, duplicate check and compensation path as a normal upload.
     * Bulk-imported books have no uploader ({@code uploaded_by = NULL}).
     */
    @Transactional
    public BookDetailDto createBookFromStream(InputStream content, String originalFilename, BookMetadataRequest metadata) {
        StoredFileInfo storedFile = storeUniqueBookFile(content, originalFilename);
        return persistNewBook(storedFile, null, metadata, null);
    }

    private StoredFileInfo storeUniqueBookFile(InputStream content, String originalFilename) {
        StoredFileInfo storedFile = fileStorageService.storeFile(content, originalFilename, StorageCategory.BOOK);
        if (bookRepository.existsByChecksumSha256(storedFile.checksumSha256())) {
            fileStorageService.deleteStoredFile(storedFile.relativePath());
            throw AppException.conflict("DUPLICATE_FILE", "A book with the same file content already exists (it may be in the trash)");
        }
        return storedFile;
    }

    private BookDetailDto persistNewBook(
            StoredFileInfo storedFile,
            StoredFileInfo storedCover,
            BookMetadataRequest metadata,
            UserPrincipal principal) {
        try {
            BookCategory category = metadata.categoryId() != null
                    ? categoryService.getBookCategoryEntity(metadata.categoryId())
                    : null;

            User uploadedBy = principal != null && principal.getId() != null
                    ? userRepository.findById(principal.getId()).orElse(null)
                    : null;

            Book book = new Book();
            book.setTitle(metadata.title().trim());
            book.setAuthor(metadata.author() != null ? metadata.author().trim() : null);
            book.setPublisher(metadata.publisher() != null ? metadata.publisher().trim() : null);
            book.setPublicationYear(metadata.publicationYear());
            book.setDescription(metadata.description() != null ? metadata.description().trim() : null);
            book.setBookType(metadata.resolveBookType());
            book.setCategory(category);
            book.setGradeLevel(metadata.resolveGradeLevel());
            book.setCurriculum(metadata.curriculum() != null ? metadata.curriculum().trim() : null);
            book.setVisibility(metadata.resolveVisibility());

            book.setOriginalName(storedFile.originalFilename());
            book.setStoredPath(storedFile.relativePath());
            book.setMimeType(storedFile.mimeType());
            book.setFileSize(storedFile.sizeBytes());
            book.setChecksumSha256(storedFile.checksumSha256());
            if (storedCover != null) {
                book.setCoverPath(storedCover.relativePath());
            }
            book.setUploadedBy(uploadedBy);

            Book saved = bookRepository.save(book);
            return BookDetailDto.from(saved);
        } catch (Exception ex) {
            // Compensation: Clean up stored files
            fileStorageService.deleteStoredFile(storedFile.relativePath());
            if (storedCover != null) {
                fileStorageService.deleteStoredFile(storedCover.relativePath());
            }
            throw ex;
        }
    }

    @Transactional
    public BookDetailDto updateBook(Long id, UpdateBookRequest request, UserPrincipal principal) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        checkOwnership(book, principal);

        if (request.title() != null && !request.title().isBlank()) {
            book.setTitle(request.title().trim());
        }
        if (request.author() != null) {
            book.setAuthor(request.author().trim());
        }
        if (request.publisher() != null) {
            book.setPublisher(request.publisher().trim());
        }
        if (request.publicationYear() != null) {
            book.setPublicationYear(request.publicationYear());
        }
        if (request.description() != null) {
            book.setDescription(request.description().trim());
        }
        if (request.bookType() != null) {
            book.setBookType(request.bookType());
        }
        if (request.categoryId() != null) {
            BookCategory category = categoryService.getBookCategoryEntity(request.categoryId());
            book.setCategory(category);
        }
        if (request.gradeLevel() != null) {
            book.setGradeLevel(request.gradeLevel());
        }
        if (request.curriculum() != null) {
            book.setCurriculum(request.curriculum().trim());
        }
        if (request.visibility() != null) {
            book.setVisibility(request.visibility());
        }

        Book updated = bookRepository.save(book);
        return BookDetailDto.from(updated);
    }

    @Transactional
    public BookDetailDto replaceBookFile(Long id, MultipartFile file, UserPrincipal principal) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        checkOwnership(book, principal);

        if (file == null || file.isEmpty()) {
            throw AppException.badRequest("FILE_REQUIRED", "New file is required to replace book content");
        }

        StoredFileInfo newStored;
        try {
            newStored = fileStorageService.storeFile(file.getInputStream(), file.getOriginalFilename(), StorageCategory.BOOK);
        } catch (IOException ex) {
            throw AppException.badRequest("STORAGE_FAILED", "Failed to stream uploaded file");
        }

        // Deduplication check (exclude current book's existing checksum)
        if (!newStored.checksumSha256().equalsIgnoreCase(book.getChecksumSha256())
                && bookRepository.existsByChecksumSha256(newStored.checksumSha256())) {
            fileStorageService.deleteStoredFile(newStored.relativePath());
            throw AppException.conflict("DUPLICATE_FILE", "A book with the same file content already exists (it may be in the trash)");
        }

        String oldPath = book.getStoredPath();
        // Decided when the transaction ends: committed → the old file goes; rolled back (any
        // failure below, or the commit itself) → the new file goes. The row never points at a
        // deleted file, and no orphan is left behind.
        cleanUpOnCompletion(oldPath, newStored.relativePath());

        book.setOriginalName(newStored.originalFilename());
        book.setStoredPath(newStored.relativePath());
        book.setMimeType(newStored.mimeType());
        book.setFileSize(newStored.sizeBytes());
        book.setChecksumSha256(newStored.checksumSha256());

        Book saved = bookRepository.save(book);
        return BookDetailDto.from(saved);
    }

    private void cleanUpOnCompletion(String deleteIfCommitted, String deleteIfRolledBack) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deleteQuietly(status == STATUS_COMMITTED ? deleteIfCommitted : deleteIfRolledBack);
            }
        });
    }

    private void deleteQuietly(String relativePath) {
        try {
            fileStorageService.deleteStoredFile(relativePath);
        } catch (RuntimeException ex) {
            log.warn("Could not delete stored file {}: {}", relativePath, ex.getMessage());
        }
    }

    @Transactional
    public void deleteBook(Long id, UserPrincipal principal) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        checkOwnership(book, principal);

        book.setDeletedAt(Instant.now());
        bookRepository.save(book);
    }

    @Transactional
    public BookDetailDto restoreBook(Long id) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        book.setDeletedAt(null);
        Book restored = bookRepository.save(book);
        return BookDetailDto.from(restored);
    }

    // ---- trash ----

    /** Soft-deleted books, most recently deleted first. */
    @Transactional(readOnly = true)
    public PageResponse<TrashItemDto> listTrash(Pageable pageable) {
        Pageable byDeletedAt = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("deletedAt"), Sort.Order.desc("id")));
        return PageResponse.from(bookRepository.findByDeletedAtIsNotNull(byDeletedAt).map(this::toTrashItem));
    }

    /** Permanently deletes one book from the trash. 404 if it isn't in the trash (active, or already purged). */
    @Transactional
    public void purgeBook(Long id) {
        Book book = bookRepository.findByIdAndDeletedAtIsNotNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found in the trash with id: " + id));
        purge(List.of(book));
    }

    /** Permanently deletes every book in the trash; returns how many. */
    @Transactional
    public int emptyTrash() {
        return purge(bookRepository.findByDeletedAtIsNotNull());
    }

    /** Permanently deletes books trashed before {@code cutoff} (nightly job); returns how many. */
    @Transactional
    public int purgeDeletedBefore(Instant cutoff) {
        return purge(bookRepository.findByDeletedAtBefore(cutoff));
    }

    /**
     * Deletes the rows (and, via {@link ItemsPurgedEvent}, their download logs) in this
     * transaction, then the files once it commits. A rollback leaves both in place. The drive
     * is checked first, so an unplugged drive fails with 503 instead of orphaning files.
     */
    private int purge(List<Book> books) {
        if (books.isEmpty()) {
            return 0;
        }
        fileStorageService.ensureMounted();
        List<String> paths = new ArrayList<>();
        for (Book book : books) {
            paths.add(book.getStoredPath());
            if (book.getCoverPath() != null && !book.getCoverPath().isBlank()) {
                paths.add(book.getCoverPath());
            }
        }
        eventPublisher.publishEvent(new ItemsPurgedEvent(ItemType.BOOK, books.stream().map(Book::getId).toList()));
        bookRepository.deleteAllInBatch(books);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paths.forEach(BookService.this::deleteQuietly);
            }
        });
        log.info("Purged {} book(s) from the trash", books.size());
        return books.size();
    }

    private TrashItemDto toTrashItem(Book book) {
        return new TrashItemDto(
                book.getId(),
                book.getTitle(),
                book.getBookType() != null ? book.getBookType().name() : null,
                book.getOriginalName(),
                book.getMimeType(),
                book.getFileSize(),
                book.getUploadedBy() != null ? book.getUploadedBy().getFullName() : null,
                book.getDeletedAt(),
                trashProperties.purgeAt(book.getDeletedAt()));
    }

    @Transactional(readOnly = true)
    public PageResponse<BookSummaryDto> listMyBooks(UserPrincipal principal, Pageable pageable) {
        if (principal == null || principal.getId() == null) {
            throw AppException.forbidden("Authentication required to view uploaded books");
        }

        Page<Book> page = bookRepository.findByUploadedByIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                principal.getId(),
                pageable
        );
        return PageResponse.from(page.map(BookSummaryDto::from));
    }

    /** Count and bytes of book files (active and in the trash), for the storage report. */
    @Transactional(readOnly = true)
    public StorageUsage storageUsage() {
        Object[] active = bookRepository.sumActiveFileSize().get(0);
        Object[] trash = bookRepository.sumDeletedFileSize().get(0);
        return StorageUsage.EMPTY
                .withActive(((Number) active[0]).longValue(), ((Number) active[1]).longValue())
                .withTrash(((Number) trash[0]).longValue(), ((Number) trash[1]).longValue());
    }

    private void checkOwnership(Book book, UserPrincipal principal) {
        if (principal == null) {
            throw AppException.forbidden("Authentication required");
        }
        if (principal.getRole() == Role.LIBRARIAN) {
            return; // Librarian has full permissions
        }
        if (book.getUploadedBy() == null || !book.getUploadedBy().getId().equals(principal.getId())) {
            throw AppException.forbidden("You do not have permission to modify this book");
        }
    }

    private Pageable resolveNativePageable(BookSearchCriteria criteria, Pageable pageable) {
        String sortKey = criteria != null && criteria.sort() != null ? criteria.sort().trim().toLowerCase() : "newest";
        Sort sort = switch (sortKey) {
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "popular" -> Sort.by(Sort.Direction.DESC, "download_count");
            default -> Sort.by(Sort.Direction.DESC, "created_at");
        };
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private Pageable resolveJpaPageable(BookSearchCriteria criteria, Pageable pageable) {
        String sortKey = criteria != null && criteria.sort() != null ? criteria.sort().trim().toLowerCase() : "newest";
        Sort sort = switch (sortKey) {
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "popular" -> Sort.by(Sort.Direction.DESC, "downloadCount");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}

