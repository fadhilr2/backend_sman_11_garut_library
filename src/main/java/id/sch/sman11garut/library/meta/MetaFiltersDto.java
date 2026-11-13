package id.sch.sman11garut.library.meta;

import java.util.List;

public record MetaFiltersDto(
        List<String> gradeLevels,
        List<String> bookTypes,
        List<String> mediaTypes,
        List<String> curricula) {
}

