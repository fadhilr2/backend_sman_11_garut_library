package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.category.ArchiveCategory;
import id.sch.sman11garut.library.category.ArchiveCategoryRepository;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.common.StorageUsage;
import id.sch.sman11garut.library.common.TrashItemDto;
import id.sch.sman11garut.library.common.Visibility;
import id.sch.sman11garut.library.config.TrashProperties;
import id.sch.sman11garut.library.media.ArchiveItemCreatedEvent;
import id.sch.sman11garut.library.stats.DownloadEvent;
import id.sch.sman11garut.library.stats.ItemType;
import id.sch.sman11garut.library.stats.ItemsPurgedEvent;
import id.sch.sman11garut.library.storage.FileStorageService;
import id.sch.sman11garut.library.storage.ServedFile;
import id.sch.sman11garut.library.storage.StorageCategory;
import id.sch.sman11garut.library.storage.StoredFileInfo;
import id.sch.sman11garut.library.user.Role;
import id.sch.sman11garut.library.user.User;
import id.sch.sman11garut.library.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    private static final String SVG_IMAGE = """
            <svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400">
              <rect width="400" height="400" fill="#2b6cb0"/>
              <text x="200" y="210" font-family="sans-serif" font-size="22" fill="#bee3f8" text-anchor="middle">IMAGE</text>
            </svg>
            """;

    private static final String SVG_VIDEO = """
            <svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400">
              <rect width="400" height="400" fill="#276749"/>
              <text x="200" y="210" font-family="sans-serif" font-size="22" fill="#c6f6d5" text-anchor="middle">VIDEO</text>
            </svg>
            """;

    private static final String SVG_DOCUMENT = """
            <svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400">
              <rect width="400" height="400" fill="#744210"/>
              <text x="200" y="210" font-family="sans-serif" font-size="22" fill="#fefcbf" text-anchor="middle">DOCUMENT</text>
            </svg>
            """;

    private final ArchiveRepository archiveRepository;
    private final ArchiveCategoryRepository archiveCategoryRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher eventPublisher;
    private final TrashProperties trashProperties;

    public ArchiveService(
            ArchiveRepository archiveRepository,
            ArchiveCategoryRepository archiveCategoryRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            ApplicationEventPublisher eventPublisher,
            TrashProperties trashProperties) {
        this.archiveRepository = archiveRepository;
        this.archiveCategoryRepository = archiveCategoryRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.eventPublisher = eventPublisher;
        this.trashProperties = trashProperties;
    }

    @Transactional(readOnly = true)
    public PageResponse<ArchiveSummaryDto> listArchiveItems(
            ArchiveSearchCriteria criteria, boolean isStaff, Pageable pageable) {
        Page<ArchiveItem> pageResult;
        String query = criteria != null && criteria.q() != null ? criteria.q().trim() : null;

        if (query != null && query.length() >= 3) {
            Pageable nativePageable = resolveNativePageable(criteria, pageable);
            String mediaTypeStr = criteria.mediaType() != null ? criteria.mediaType().name() : null;
            String fromStr = criteria.from() != null ? criteria.from().toString() : null;
            String toStr = criteria.to() != null ? criteria.to().toString() : null;
            pageResult = archiveRepository.searchFulltext(
                    query,
                    criteria.categoryId(),
                    mediaTypeStr,
                    fromStr,
                    toStr,
                    isStaff,
                    nativePageable
            );
        } else {
            Pageable jpaPageable = resolveJpaPageable(criteria, pageable);
            pageResult = archiveRepository.findAll(
                    ArchiveSpecifications.withCriteria(criteria, isStaff),
                    jpaPageable
            );
        }

        return PageResponse.from(pageResult.map(ArchiveSummaryDto::from));
    }

    @Transactional(readOnly = true)
    public ArchiveDetailDto getArchiveItem(Long id, boolean isStaff) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        if (item.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Archive item not found with id: " + id);
        }

        return ArchiveDetailDto.from(item);
    }

    // The open* methods only look up and check the item. The controller streams the result
    // after the transaction has ended, so a long video never holds a DB connection.

    @Transactional(readOnly = true)
    public ServedFile openFile(Long id, boolean isStaff) {
        ArchiveItem item = findVisibleItem(id, isStaff);
        return new ServedFile(
                fileStorageService.loadAsResource(item.getStoredPath()), item.getMimeType(), item.getOriginalName());
    }

    /** Same as {@link #openFile}, and counts the download (asynchronously). */
    @Transactional(readOnly = true)
    public ServedFile openDownload(Long id, boolean isStaff) {
        ServedFile file = openFile(id, isStaff);
        eventPublisher.publishEvent(new DownloadEvent(ItemType.ARCHIVE, id, Instant.now()));
        return file;
    }

    /** The generated thumbnail, or a placeholder per media type. STAFF items stay hidden (404) from anonymous callers. */
    @Transactional(readOnly = true)
    public ServedFile openThumbnail(Long id, boolean isStaff) {
        ArchiveItem item = findVisibleItem(id, isStaff);
        if (item.getThumbnailPath() != null && !item.getThumbnailPath().isBlank()) {
            try {
                return new ServedFile(
                        fileStorageService.loadAsResource(item.getThumbnailPath()),
                        ServedFile.imageTypeOf(item.getThumbnailPath()),
                        null);
            } catch (AppException ex) {
                log.debug("Thumbnail for archive item {} unavailable ({}), serving placeholder", id, ex.getCode());
            }
        }
        return ServedFile.svg(switch (item.getMediaType()) {
            case IMAGE -> SVG_IMAGE;
            case VIDEO -> SVG_VIDEO;
            default -> SVG_DOCUMENT;
        });
    }

    private ArchiveItem findVisibleItem(Long id, boolean isStaff) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));
        if (item.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Archive item not found with id: " + id);
        }
        return item;
    }

    @Transactional
    public ArchiveDetailDto createArchiveItem(
            MultipartFile file,
            ArchiveMetadataRequest metadata,
            UserPrincipal principal) {

        if (file == null || file.isEmpty()) {
            throw AppException.badRequest("FILE_REQUIRED", "Archive file is required");
        }

        StoredFileInfo storedFile;
        try {
            storedFile = storeUniqueArchiveFile(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException ex) {
            throw AppException.badRequest("STORAGE_FAILED", "Failed to stream uploaded archive file");
        }

        return persistNewArchiveItem(storedFile, metadata, principal);
    }

    /**
     * Creates an archive item from a raw stream (used by bulk import). Same storage,
     * duplicate check, compensation and thumbnail event as a normal upload.
     * Bulk-imported items have no uploader ({@code uploaded_by = NULL}).
     */
    @Transactional
    public ArchiveDetailDto createArchiveItemFromStream(
            InputStream content, String originalFilename, ArchiveMetadataRequest metadata) {
        StoredFileInfo storedFile = storeUniqueArchiveFile(content, originalFilename);
        return persistNewArchiveItem(storedFile, metadata, null);
    }

    private StoredFileInfo storeUniqueArchiveFile(InputStream content, String originalFilename) {
        StoredFileInfo storedFile = fileStorageService.storeFile(content, originalFilename, StorageCategory.ARCHIVE);
        if (archiveRepository.existsByChecksumSha256(storedFile.checksumSha256())) {
            fileStorageService.deleteStoredFile(storedFile.relativePath());
            throw AppException.conflict("DUPLICATE_FILE", "An archive item with the same file content already exists (it may be in the trash)");
        }
        return storedFile;
    }

    private ArchiveDetailDto persistNewArchiveItem(
            StoredFileInfo storedFile, ArchiveMetadataRequest metadata, UserPrincipal principal) {
        try {
            ArchiveCategory category = metadata.categoryId() != null
                    ? archiveCategoryRepository.findById(metadata.categoryId())
                            .orElseThrow(() -> AppException.notFound("Archive category not found with id: " + metadata.categoryId()))
                    : null;

            User uploadedBy = principal != null && principal.getId() != null
                    ? userRepository.findById(principal.getId()).orElse(null)
                    : null;

            MediaType mediaType = MediaType.resolveFromMime(storedFile.mimeType());

            ArchiveItem item = new ArchiveItem();
            item.setTitle(metadata.title().trim());
            item.setDescription(metadata.description() != null ? metadata.description().trim() : null);
            item.setMediaType(mediaType);
            item.setCategory(category);
            item.setEventDate(metadata.eventDate());
            item.setVisibility(metadata.resolveVisibility());
            item.setOriginalName(storedFile.originalFilename());
            item.setStoredPath(storedFile.relativePath());
            item.setMimeType(storedFile.mimeType());
            item.setFileSize(storedFile.sizeBytes());
            item.setChecksumSha256(storedFile.checksumSha256());
            item.setUploadedBy(uploadedBy);

            ArchiveItem saved = archiveRepository.save(item);

            eventPublisher.publishEvent(new ArchiveItemCreatedEvent(
                    saved.getId(), saved.getStoredPath(), saved.getMediaType(), saved.getMimeType()));

            return ArchiveDetailDto.from(saved);
        } catch (Exception ex) {
            fileStorageService.deleteStoredFile(storedFile.relativePath());
            throw ex;
        }
    }

    @Transactional
    public ArchiveDetailDto updateArchiveItem(Long id, UpdateArchiveRequest request, UserPrincipal principal) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        checkOwnership(item, principal);

        if (request.title() != null && !request.title().isBlank()) {
            item.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            item.setDescription(request.description().trim());
        }
        if (request.categoryId() != null) {
            ArchiveCategory category = archiveCategoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> AppException.notFound("Archive category not found with id: " + request.categoryId()));
            item.setCategory(category);
        }
        if (request.eventDate() != null) {
            item.setEventDate(request.eventDate());
        }
        if (request.visibility() != null) {
            item.setVisibility(request.visibility());
        }

        ArchiveItem updated = archiveRepository.save(item);
        return ArchiveDetailDto.from(updated);
    }

    @Transactional
    public void deleteArchiveItem(Long id, UserPrincipal principal) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        checkOwnership(item, principal);

        item.setDeletedAt(Instant.now());
        archiveRepository.save(item);
    }

    @Transactional
    public ArchiveDetailDto restoreArchiveItem(Long id) {
        ArchiveItem item = archiveRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        item.setDeletedAt(null);
        ArchiveItem restored = archiveRepository.save(item);
        return ArchiveDetailDto.from(restored);
    }

    // ---- trash ----

    /** Soft-deleted archive items, most recently deleted first. */
    @Transactional(readOnly = true)
    public PageResponse<TrashItemDto> listTrash(Pageable pageable) {
        Pageable byDeletedAt = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("deletedAt"), Sort.Order.desc("id")));
        return PageResponse.from(archiveRepository.findByDeletedAtIsNotNull(byDeletedAt).map(this::toTrashItem));
    }

    /** Permanently deletes one archive item from the trash. 404 if it isn't in the trash. */
    @Transactional
    public void purgeArchiveItem(Long id) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNotNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found in the trash with id: " + id));
        purge(List.of(item));
    }

    /** Permanently deletes every archive item in the trash; returns how many. */
    @Transactional
    public int emptyTrash() {
        return purge(archiveRepository.findByDeletedAtIsNotNull());
    }

    /** Permanently deletes archive items trashed before {@code cutoff} (nightly job); returns how many. */
    @Transactional
    public int purgeDeletedBefore(Instant cutoff) {
        return purge(archiveRepository.findByDeletedAtBefore(cutoff));
    }

    /** Same order as {@code BookService.purge}: drive check, rows and logs in the transaction, files after commit. */
    private int purge(List<ArchiveItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        fileStorageService.ensureMounted();
        List<String> paths = new ArrayList<>();
        for (ArchiveItem item : items) {
            paths.add(item.getStoredPath());
            if (item.getThumbnailPath() != null && !item.getThumbnailPath().isBlank()) {
                paths.add(item.getThumbnailPath());
            }
        }
        eventPublisher.publishEvent(new ItemsPurgedEvent(ItemType.ARCHIVE, items.stream().map(ArchiveItem::getId).toList()));
        archiveRepository.deleteAllInBatch(items);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paths.forEach(ArchiveService.this::deleteQuietly);
            }
        });
        log.info("Purged {} archive item(s) from the trash", items.size());
        return items.size();
    }

    private void deleteQuietly(String relativePath) {
        try {
            fileStorageService.deleteStoredFile(relativePath);
        } catch (RuntimeException ex) {
            log.warn("Could not delete stored file {}: {}", relativePath, ex.getMessage());
        }
    }

    private TrashItemDto toTrashItem(ArchiveItem item) {
        return new TrashItemDto(
                item.getId(),
                item.getTitle(),
                item.getMediaType() != null ? item.getMediaType().name() : null,
                item.getOriginalName(),
                item.getMimeType(),
                item.getFileSize(),
                item.getUploadedBy() != null ? item.getUploadedBy().getFullName() : null,
                item.getDeletedAt(),
                trashProperties.purgeAt(item.getDeletedAt()));
    }

    @Transactional(readOnly = true)
    public PageResponse<ArchiveSummaryDto> listMyArchiveItems(UserPrincipal principal, Pageable pageable) {
        if (principal == null || principal.getId() == null) {
            throw AppException.forbidden("Authentication required to view uploaded archive items");
        }
        Page<ArchiveItem> page = archiveRepository.findByUploadedByIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                principal.getId(), pageable);
        return PageResponse.from(page.map(ArchiveSummaryDto::from));
    }

    /** Count and bytes of archive files per media type (active and in the trash), for the storage report. */
    @Transactional(readOnly = true)
    public Map<MediaType, StorageUsage> storageUsageByMediaType() {
        Map<MediaType, StorageUsage> usage = new EnumMap<>(MediaType.class);
        for (MediaType type : MediaType.values()) {
            usage.put(type, StorageUsage.EMPTY);
        }
        for (Object[] row : archiveRepository.sumActiveFileSizeByMediaType()) {
            MediaType type = (MediaType) row[0];
            usage.put(type, usage.get(type).withActive(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        for (Object[] row : archiveRepository.sumDeletedFileSizeByMediaType()) {
            MediaType type = (MediaType) row[0];
            usage.put(type, usage.get(type).withTrash(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return usage;
    }

    private void checkOwnership(ArchiveItem item, UserPrincipal principal) {
        if (principal == null) {
            throw AppException.forbidden("Authentication required");
        }
        if (principal.getRole() == Role.LIBRARIAN) {
            return;
        }
        if (item.getUploadedBy() == null || !item.getUploadedBy().getId().equals(principal.getId())) {
            throw AppException.forbidden("You do not have permission to modify this archive item");
        }
    }

    private Pageable resolveNativePageable(ArchiveSearchCriteria criteria, Pageable pageable) {
        String sortKey = criteria != null && criteria.sort() != null ? criteria.sort().trim().toLowerCase() : "newest";
        Sort sort = switch (sortKey) {
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "popular" -> Sort.by(Sort.Direction.DESC, "view_count");
            case "date" -> Sort.by(Sort.Direction.DESC, "event_date");
            default -> Sort.by(Sort.Direction.DESC, "created_at");
        };
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private Pageable resolveJpaPageable(ArchiveSearchCriteria criteria, Pageable pageable) {
        String sortKey = criteria != null && criteria.sort() != null ? criteria.sort().trim().toLowerCase() : "newest";
        Sort sort = switch (sortKey) {
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            case "popular" -> Sort.by(Sort.Direction.DESC, "viewCount");
            case "date" -> Sort.by(Sort.Direction.DESC, "eventDate");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
