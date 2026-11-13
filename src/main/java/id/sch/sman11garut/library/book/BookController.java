package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.user.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<BookSummaryDto>> listBooks(
            @ModelAttribute BookSearchCriteria criteria,
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(bookService.listBooks(criteria, isStaff, pageable));
    }

    @GetMapping("/mine")
    public ResponseEntity<PageResponse<BookSummaryDto>> listMyBooks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(bookService.listMyBooks(principal, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookDetailDto> getBook(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(bookService.getBook(id, isStaff));
    }

    @GetMapping("/{id}/file")
    public void streamFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean isStaff = isStaffUser(principal);
        bookService.streamBookFile(id, isStaff, request, response);
    }

    @GetMapping("/{id}/download")
    public void downloadFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean isStaff = isStaffUser(principal);
        bookService.downloadBookFile(id, isStaff, request, response);
    }

    @GetMapping("/{id}/cover")
    public void getCover(
            @PathVariable Long id,
            HttpServletRequest request,
            HttpServletResponse response) {
        bookService.getBookCover(id, request, response);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookDetailDto> createBook(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestPart("metadata") @Valid BookMetadataRequest metadata,
            @AuthenticationPrincipal UserPrincipal principal) {
        BookDetailDto created = bookService.createBook(file, cover, metadata, principal);
        return ResponseEntity.created(URI.create("/api/books/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BookDetailDto> updateBook(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBookRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(bookService.updateBook(id, request, principal));
    }

    @PutMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookDetailDto> replaceFile(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(bookService.replaceBookFile(id, file, principal));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBook(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        bookService.deleteBook(id, principal);
        return ResponseEntity.noContent().build();
    }

    private static boolean isStaffUser(UserPrincipal principal) {
        return principal != null && (principal.getRole() == Role.TEACHER || principal.getRole() == Role.LIBRARIAN);
    }
}

