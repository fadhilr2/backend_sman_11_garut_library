package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.storage.RangeFileResponder;
import id.sch.sman11garut.library.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
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
@Tag(name = "Books", description = "Ebooks, modules, question banks and slides")
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;
    private final RangeFileResponder rangeFileResponder;

    public BookController(BookService bookService, RangeFileResponder rangeFileResponder) {
        this.bookService = bookService;
        this.rangeFileResponder = rangeFileResponder;
    }

    @Operation(summary = "Search and list books (PUBLIC only unless the caller is staff)")
    @GetMapping
    public ResponseEntity<PageResponse<BookSummaryDto>> listBooks(
            @ParameterObject @ModelAttribute BookSearchCriteria criteria,
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(bookService.listBooks(criteria, isStaff, pageable));
    }

    @Operation(summary = "Books uploaded by the current user")
    @GetMapping("/mine")
    public ResponseEntity<PageResponse<BookSummaryDto>> listMyBooks(
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(bookService.listMyBooks(principal, pageable));
    }

    @Operation(summary = "Book metadata")
    @GetMapping("/{id}")
    public ResponseEntity<BookDetailDto> getBook(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(bookService.getBook(id, isStaff));
    }

    @Operation(summary = "Read the book inline (supports HTTP Range)")
    @ApiResponse(responseCode = "200", description = "The whole file, with the stored file's Content-Type",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Partial content for a `Range` request",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/file")
    public void streamFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        rangeFileResponder.serve(request, response, bookService.openFile(id, isStaffUser(principal)));
    }

    @Operation(summary = "Download the book as an attachment (counted)")
    @ApiResponse(responseCode = "200", description = "The file with Content-Disposition: attachment",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Partial content for a `Range` request",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/download")
    public void downloadFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        rangeFileResponder.serveAttachment(request, response, bookService.openDownload(id, isStaffUser(principal)));
    }

    @Operation(summary = "Cover image (JPEG/PNG), or an SVG placeholder")
    @ApiResponse(responseCode = "200", description = "Cover image or SVG placeholder",
            content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/cover")
    public void getCover(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        rangeFileResponder.serve(request, response, bookService.openCover(id, isStaffUser(principal)));
    }

    @Operation(summary = "Upload a book (multipart: file, optional cover, metadata JSON)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            encoding = {
                    @Encoding(name = "metadata", contentType = "application/json")
            }))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookDetailDto> createBook(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestPart("metadata") @Valid BookMetadataRequest metadata,
            @AuthenticationPrincipal UserPrincipal principal) {
        BookDetailDto created = bookService.createBook(file, cover, metadata, principal);
        return ResponseEntity.created(URI.create("/api/books/" + created.id())).body(created);
    }

    @Operation(summary = "Update book metadata (owner or librarian)")
    @PutMapping("/{id}")
    public ResponseEntity<BookDetailDto> updateBook(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBookRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(bookService.updateBook(id, request, principal));
    }

    @Operation(summary = "Replace the book file (owner or librarian)")
    @PutMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookDetailDto> replaceFile(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(bookService.replaceBookFile(id, file, principal));
    }

    @Operation(summary = "Set or replace the cover image, JPEG or PNG (owner or librarian)")
    @PutMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookDetailDto> replaceCover(
            @PathVariable Long id,
            @RequestPart("cover") MultipartFile cover,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(bookService.replaceBookCover(id, cover, principal));
    }

    @Operation(summary = "Soft-delete a book (owner or librarian)")
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

