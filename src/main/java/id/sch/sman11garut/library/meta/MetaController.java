package id.sch.sman11garut.library.meta;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Meta", description = "Values for filter dropdowns")
@RequestMapping("/api/meta")
public class MetaController {

    private static final MetaFiltersDto FILTERS = new MetaFiltersDto(
            List.of("X", "XI", "XII", "ALL"),
            List.of("EBOOK", "TEACHING_MODULE", "QUESTION_BANK", "SLIDES", "OTHER"),
            List.of("IMAGE", "VIDEO", "DOCUMENT"),
            List.of("K13", "Kurikulum Merdeka", "Umum")
    );

    @Operation(summary = "Grade levels, book types, media types and curricula")
    @GetMapping("/filters")
    public ResponseEntity<MetaFiltersDto> getFilters() {
        return ResponseEntity.ok(FILTERS);
    }
}

