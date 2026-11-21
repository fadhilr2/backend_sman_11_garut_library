package id.sch.sman11garut.library.book;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: Books", description = "Librarian only")
@RequestMapping("/api/admin/books")
@PreAuthorize("hasRole('LIBRARIAN')")
public class BookAdminController {

    private final BookService bookService;

    public BookAdminController(BookService bookService) {
        this.bookService = bookService;
    }

    @Operation(summary = "Restore a soft-deleted book")
    @PostMapping("/{id}/restore")
    public ResponseEntity<BookDetailDto> restoreBook(@PathVariable Long id) {
        return ResponseEntity.ok(bookService.restoreBook(id));
    }
}

