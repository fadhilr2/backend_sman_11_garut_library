package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.category.ArchiveCategory;
import id.sch.sman11garut.library.category.ArchiveCategoryRepository;
import id.sch.sman11garut.library.common.AppException;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.common.Visibility;
import id.sch.sman11garut.library.media.ArchiveItemCreatedEvent;
import id.sch.sman11garut.library.stats.DownloadEvent;
import id.sch.sman11garut.library.stats.ItemType;
import id.sch.sman11garut.library.storage.FileStorageService;
import id.sch.sman11garut.library.storage.RangeFileResponder;
import id.sch.sman11garut.library.storage.StorageCategory;
import id.sch.sman11garut.library.storage.StoredFileInfo;
import id.sch.sman11garut.library.user.Role;
import id.sch.sman11garut.library.user.User;
import id.sch.sman11garut.library.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final RangeFileResponder rangeFileResponder;
    private final ApplicationEventPublisher eventPublisher;

    public ArchiveService(
            ArchiveRepository archiveRepository,
            ArchiveCategoryRepository archiveCategoryRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            RangeFileResponder rangeFileResponder,
            ApplicationEventPublisher eventPublisher) {
        this.archiveRepository = archiveRepository;
        this.archiveCategoryRepository = archiveCategoryRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.rangeFileResponder = rangeFileResponder;
        this.eventPublisher = eventPublisher;
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

    @Transactional(readOnly = true)
    public void streamArchiveFile(Long id, boolean isStaff, HttpServletRequest request, HttpServletResponse response) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        if (item.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Archive item not found with id: " + id);
        }

        Resource resource = fileStorageService.loadAsResource(item.getStoredPath());
        try {
            rangeFileResponder.write(request, response, resource, item.getMimeType());
        } catch (IOException ex) {
            log.error("Streaming failed for archive item id {}: {}", id, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void downloadArchiveFile(Long id, boolean isStaff, HttpServletRequest request, HttpServletResponse response) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        if (item.getVisibility() == Visibility.STAFF && !isStaff) {
            throw AppException.notFound("Archive item not found with id: " + id);
        }

        eventPublisher.publishEvent(new DownloadEvent(ItemType.ARCHIVE, item.getId(), Instant.now()));

        Resource resource = fileStorageService.loadAsResource(item.getStoredPath());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + item.getOriginalName() + "\"");

        try {
            rangeFileResponder.write(request, response, resource, item.getMimeType());
        } catch (IOException ex) {
            log.error("Download failed for archive item id {}: {}", id, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void getArchiveThumbnail(Long id, HttpServletRequest request, HttpServletResponse response) {
        ArchiveItem item = archiveRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> AppException.notFound("Archive item not found with id: " + id));

        if (item.getThumbnailPath() != null && !item.getThumbnailPath().isBlank()) {
            try {
                Resource resource = fileStorageService.loadAsResource(item.getThumbnailPath());
                rangeFileResponder.write(request, response, resource, "image/jpeg");
                return;
            } catch (Exception ex) {
                log.warn("Could not serve thumbnail for archive item {}, falling back to placeholder", id);
            }
        }

        serveDefaultThumbnail(item.getMediaType(), response);
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

    @Transactional(readOnly = true)
    public PageResponse<ArchiveSummaryDto> listMyArchiveItems(UserPrincipal principal, Pageable pageable) {
        if (principal == null || principal.getId() == null) {
            throw AppException.forbidden("Authentication required to view uploaded archive items");
        }
        Page<ArchiveItem> page = archiveRepository.findByUploadedByIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                principal.getId(), pageable);
        return PageResponse.from(page.map(ArchiveSummaryDto::from));
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

    private void serveDefaultThumbnail(MediaType mediaType, HttpServletResponse response) {
        try {
            response.setContentType("image/svg+xml");
            response.setStatus(HttpServletResponse.SC_OK);
            String svg = switch (mediaType) {
                case IMAGE -> SVG_IMAGE;
                case VIDEO -> SVG_VIDEO;
                default -> SVG_DOCUMENT;
            };
            response.getOutputStream().write(svg.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
