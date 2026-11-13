package id.sch.sman11garut.library.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        CategoryType type,

        @NotBlank(message = "Category name is required")
        @Size(max = 100, message = "Category name must not exceed 100 characters")
        String name,

        Integer parentId) {

    public CategoryType resolveType() {
        return type != null ? type : CategoryType.BOOK;
    }
}

