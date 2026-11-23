package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.common.PurgeResultDto;
import id.sch.sman11garut.library.common.TrashItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    @Operation(summary = "List books in the trash, most recently deleted first")
    @GetMapping("/trash")
    public ResponseEntity<PageResponse<TrashItemDto>> listTrash(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(bookService.listTrash(pageable));
    }

    @Operation(summary = "Permanently delete one book from the trash (row and files)")
    @DeleteMapping("/trash/{id}")
    public ResponseEntity<Void> purgeBook(@PathVariable Long id) {
        bookService.purgeBook(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Empty the book trash")
    @DeleteMapping("/trash")
    public ResponseEntity<PurgeResultDto> emptyTrash() {
        return ResponseEntity.ok(new PurgeResultDto(bookService.emptyTrash()));
    }
}
