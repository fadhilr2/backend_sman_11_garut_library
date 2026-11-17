package id.sch.sman11garut.library.stats.dto;

public record TopBookDto(
        Long id,
        String title,
        String author,
        int downloadCount
) {}
