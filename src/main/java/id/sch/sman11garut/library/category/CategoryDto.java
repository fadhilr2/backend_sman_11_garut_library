package id.sch.sman11garut.library.category;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryDto(
        Integer id,
        String name,
        Integer parentId,
        List<CategoryDto> children) {

    public static CategoryDto from(BookCategory category) {
        List<CategoryDto> childDtos = category.getChildren() != null && !category.getChildren().isEmpty()
                ? category.getChildren().stream().map(CategoryDto::from).toList()
                : null;
        return new CategoryDto(category.getId(), category.getName(), category.getParentId(), childDtos);
    }

    public static CategoryDto from(ArchiveCategory category) {
        List<CategoryDto> childDtos = category.getChildren() != null && !category.getChildren().isEmpty()
                ? category.getChildren().stream().map(CategoryDto::from).toList()
                : null;
        return new CategoryDto(category.getId(), category.getName(), category.getParentId(), childDtos);
    }
}

