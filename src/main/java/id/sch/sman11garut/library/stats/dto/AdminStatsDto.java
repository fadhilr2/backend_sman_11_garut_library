package id.sch.sman11garut.library.stats.dto;

import java.util.List;
import java.util.Map;

public record AdminStatsDto(
        long totalBooks,
        long totalArchiveItems,
        long totalDownloads,
        long downloadsToday,
        long downloadsThisWeek,
        Map<String, Long> booksByType,
        Map<String, Long> archiveByMediaType,
        List<TopBookDto> topBooks,
        List<DailyTrendDto> dailyDownloads
) {}
