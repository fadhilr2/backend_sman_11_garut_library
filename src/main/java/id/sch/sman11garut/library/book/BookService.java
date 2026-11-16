package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.category.BookCategory;
import id.sch.sman11garut.library.category.CategoryService;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.storage.FileStorageService;
import id.sch.sman11garut.library.storage.RangeFileResponder;
import id.sch.sman11garut.library.storage.StorageCategory;
import id.sch.sman11garut.library.storage.StoredFileInfo;
import id.sch.sman11garut.library.user.Role;
import id.sch.sman11garut.library.user.User;
import id.sch.sman11garut.library.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final RangeFileResponder rangeFileResponder;

    public BookService(
            BookRepository bookRepository,
            CategoryService categoryService,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            RangeFileResponder rangeFileResponder) {
        this.bookRepository = bookRepository;
        this.categoryService = categoryService;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.rangeFileResponder = rangeFileResponder;
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

    @Transactional(readOnly = true)
    public void streamBookFile(Long id, boolean isStaff, HttpServletRequest request, HttpServletResponse response) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        if (book.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Book not found with id: " + id);
        }

        Resource resource = fileStorageService.loadAsResource(book.getStoredPath());
        try {
            rangeFileResponder.write(request, response, resource, book.getMimeType());
        } catch (IOException ex) {
            log.error("Streaming failed for book id {}: {}", id, ex.getMessage());
        }
    }

    @Transactional
    public void downloadBookFile(Long id, boolean isStaff, HttpServletRequest request, HttpServletResponse response) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        if (book.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Book not found with id: " + id);
        }

        bookRepository.incrementDownloadCount(book.getId());

        Resource resource = fileStorageService.loadAsResource(book.getStoredPath());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + book.getOriginalName() + "\"");

        try {
            rangeFileResponder.write(request, response, resource, book.getMimeType());
        } catch (IOException ex) {
            log.error("Download failed for book id {}: {}", id, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void getBookCover(Long id, HttpServletRequest request, HttpServletResponse response) {
        Book book = bookRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Book not found with id: " + id));

        if (book.getCoverPath() == null || book.getCoverPath().isBlank()) {
            serveDefaultCover(response);
            return;
        }

        try {
            Resource resource = fileStorageService.loadAsResource(book.getCoverPath());
            rangeFileResponder.write(request, response, resource, "image/jpeg");
        } catch (Exception ex) {
            serveDefaultCover(response);
        }
    }

    private void serveDefaultCover(HttpServletResponse response) {
        try {
            response.setContentType("image/svg+xml");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().write(DEFAULT_COVER_SVG.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
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
            storedFile = fileStorageService.storeFile(file.getInputStream(), file.getOriginalFilename(), StorageCategory.BOOK);
        } catch (IOException ex) {
            throw AppException.badRequest("STORAGE_FAILED", "Failed to stream uploaded book file");
        }

        // Deduplication check
        if (bookRepository.existsByChecksumSha256AndDeletedAtIsNull(storedFile.checksumSha256())) {
            fileStorageService.deleteStoredFile(storedFile.relativePath());
            throw AppException.conflict("DUPLICATE_FILE", "A book with the same file content already exists");
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
                && bookRepository.existsByChecksumSha256AndDeletedAtIsNull(newStored.checksumSha256())) {
            fileStorageService.deleteStoredFile(newStored.relativePath());
            throw AppException.conflict("DUPLICATE_FILE", "A book with the same file content already exists");
        }

        String oldPath = book.getStoredPath();

        book.setOriginalName(newStored.originalFilename());
        book.setStoredPath(newStored.relativePath());
        book.setMimeType(newStored.mimeType());
        book.setFileSize(newStored.sizeBytes());
        book.setChecksumSha256(newStored.checksumSha256());

        Book saved = bookRepository.save(book);

        // Delete old file
        fileStorageService.deleteStoredFile(oldPath);

        return BookDetailDto.from(saved);
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

