package id.sch.sman11garut.library.category;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Categories", description = "Book and archive category trees")
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "List categories as a tree (type BOOK or ARCHIVE)")
    @GetMapping
    public ResponseEntity<List<CategoryDto>> listCategories(
            @RequestParam(required = false, defaultValue = "BOOK") CategoryType type) {
        return ResponseEntity.ok(categoryService.listCategories(type));
    }
}

