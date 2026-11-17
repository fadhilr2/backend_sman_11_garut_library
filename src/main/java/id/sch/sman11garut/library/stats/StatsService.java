package id.sch.sman11garut.library.stats;

import id.sch.sman11garut.library.archive.ArchiveRepository;
import id.sch.sman11garut.library.archive.MediaType;
import id.sch.sman11garut.library.book.Book;
import id.sch.sman11garut.library.book.BookRepository;
import id.sch.sman11garut.library.book.BookType;
import id.sch.sman11garut.library.stats.dto.AdminStatsDto;
import id.sch.sman11garut.library.stats.dto.DailyTrendDto;
import id.sch.sman11garut.library.stats.dto.TopBookDto;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    private static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Jakarta");

    private final DownloadLogRepository downloadLogRepository;
    private final BookRepository bookRepository;
    private final ArchiveRepository archiveRepository;

    public StatsService(
            DownloadLogRepository downloadLogRepository,
            BookRepository bookRepository,
            ArchiveRepository archiveRepository) {
        this.downloadLogRepository = downloadLogRepository;
        this.bookRepository = bookRepository;
        this.archiveRepository = archiveRepository;
    }

    @Transactional(readOnly = true)
    public AdminStatsDto getAdminStats() {
        long totalBooks = bookRepository.countByDeletedAtIsNull();
        long totalArchiveItems = archiveRepository.countByDeletedAtIsNull();
        long totalDownloads = downloadLogRepository.count();

        // Downloads today starting from 00:00:00 local school time
        Instant startOfToday = LocalDate.now(LOCAL_ZONE).atStartOfDay(LOCAL_ZONE).toInstant();
        long downloadsToday = downloadLogRepository.countByDownloadedAtAfter(startOfToday);

        // Downloads past 7 days
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long downloadsThisWeek = downloadLogRepository.countByDownloadedAtAfter(sevenDaysAgo);

        // Books grouped by type
        Map<String, Long> booksByType = new LinkedHashMap<>();
        for (BookType type : BookType.values()) {
            booksByType.put(type.name(), 0L);
        }
        List<Object[]> bookTypeCounts = bookRepository.countGroupedByBookType();
        for (Object[] row : bookTypeCounts) {
            if (row != null && row.length >= 2 && row[0] != null) {
                String type = row[0].toString();
                Long count = ((Number) row[1]).longValue();
                booksByType.put(type, count);
            }
        }

        // Archive items grouped by media type
        Map<String, Long> archiveByMediaType = new LinkedHashMap<>();
        for (MediaType type : MediaType.values()) {
            archiveByMediaType.put(type.name(), 0L);
        }
        List<Object[]> mediaTypeCounts = archiveRepository.countGroupedByMediaType();
        for (Object[] row : mediaTypeCounts) {
            if (row != null && row.length >= 2 && row[0] != null) {
                String type = row[0].toString();
                Long count = ((Number) row[1]).longValue();
                archiveByMediaType.put(type, count);
            }
        }

        // Top 5 downloaded books
        List<Book> topBookEntities = bookRepository.findTop5ByDeletedAtIsNullOrderByDownloadCountDesc();
        List<TopBookDto> topBooks = topBookEntities.stream()
                .map(b -> new TopBookDto(b.getId(), b.getTitle(), b.getAuthor(), b.getDownloadCount()))
                .toList();

        // 7-day daily download trends
        List<Object[]> dailyCounts = downloadLogRepository.getDailyDownloadCounts(sevenDaysAgo);
        List<DailyTrendDto> dailyDownloads = dailyCounts.stream()
                .map(row -> new DailyTrendDto(row[0].toString(), ((Number) row[1]).longValue()))
                .toList();

        return new AdminStatsDto(
                totalBooks,
                totalArchiveItems,
                totalDownloads,
                downloadsToday,
                downloadsThisWeek,
                booksByType,
                archiveByMediaType,
                topBooks,
                dailyDownloads
        );
    }
}
