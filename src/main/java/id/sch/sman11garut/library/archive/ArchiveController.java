package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.storage.RangeFileResponder;
import id.sch.sman11garut.library.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Archive", description = "School photos, videos and documents")
@RequestMapping("/api/archive")
public class ArchiveController {

    private final ArchiveService archiveService;
    private final RangeFileResponder rangeFileResponder;

    public ArchiveController(ArchiveService archiveService, RangeFileResponder rangeFileResponder) {
        this.archiveService = archiveService;
        this.rangeFileResponder = rangeFileResponder;
    }

    @Operation(summary = "Search and list archive items (PUBLIC only unless the caller is staff)")
    @GetMapping
    public ResponseEntity<PageResponse<ArchiveSummaryDto>> listArchiveItems(
            @ParameterObject @ModelAttribute ArchiveSearchCriteria criteria,
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(archiveService.listArchiveItems(criteria, isStaff, pageable));
    }

    @Operation(summary = "Archive items uploaded by the current user")
    @GetMapping("/mine")
    public ResponseEntity<PageResponse<ArchiveSummaryDto>> listMyArchiveItems(
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(archiveService.listMyArchiveItems(principal, pageable));
    }

    @Operation(summary = "Archive item metadata (incl. width, height, duration)")
    @GetMapping("/{id}")
    public ResponseEntity<ArchiveDetailDto> getArchiveItem(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(archiveService.getArchiveItem(id, isStaff));
    }

    @Operation(summary = "Stream the item inline (Range support for video seeking)")
    @ApiResponse(responseCode = "200", description = "The whole file, with the stored file's Content-Type",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Partial content for a `Range` request",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/file")
    public void streamFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        rangeFileResponder.serve(request, response, archiveService.openFile(id, isStaffUser(principal)));
    }

    @Operation(summary = "Download the item as an attachment (counted)")
    @ApiResponse(responseCode = "200", description = "The file with Content-Disposition: attachment",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Partial content for a `Range` request",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/download")
    public void downloadFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        rangeFileResponder.serveAttachment(request, response, archiveService.openDownload(id, isStaffUser(principal)));
    }

    @Operation(summary = "Thumbnail (JPEG), or an SVG placeholder per media type")
    @ApiResponse(responseCode = "200", description = "Thumbnail or SVG placeholder",
            content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/thumbnail")
    public void getThumbnail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        rangeFileResponder.serve(request, response, archiveService.openThumbnail(id, isStaffUser(principal)));
    }

    @Operation(summary = "Upload an archive item (multipart: file, metadata JSON); media type is detected")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            encoding = {
                    @Encoding(name = "metadata", contentType = "application/json")
            }))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArchiveDetailDto> createArchiveItem(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") @Valid ArchiveMetadataRequest metadata,
            @AuthenticationPrincipal UserPrincipal principal) {
        ArchiveDetailDto created = archiveService.createArchiveItem(file, metadata, principal);
        return ResponseEntity.created(URI.create("/api/archive/" + created.id())).body(created);
    }

    @Operation(summary = "Update archive item metadata (owner or librarian)")
    @PutMapping("/{id}")
    public ResponseEntity<ArchiveDetailDto> updateArchiveItem(
            @PathVariable Long id,
            @Valid @RequestBody UpdateArchiveRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(archiveService.updateArchiveItem(id, request, principal));
    }

    @Operation(summary = "Soft-delete an archive item (owner or librarian)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteArchiveItem(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        archiveService.deleteArchiveItem(id, principal);
        return ResponseEntity.noContent().build();
    }

    private static boolean isStaffUser(UserPrincipal principal) {
        return principal != null && (principal.getRole() == Role.TEACHER || principal.getRole() == Role.LIBRARIAN);
    }
}
