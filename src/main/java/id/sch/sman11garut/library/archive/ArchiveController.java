package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.auth.UserPrincipal;
import id.sch.sman11garut.library.common.PageResponse;
import id.sch.sman11garut.library.user.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
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
@RequestMapping("/api/archive")
public class ArchiveController {

    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<ArchiveSummaryDto>> listArchiveItems(
            @ModelAttribute ArchiveSearchCriteria criteria,
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(archiveService.listArchiveItems(criteria, isStaff, pageable));
    }

    @GetMapping("/mine")
    public ResponseEntity<PageResponse<ArchiveSummaryDto>> listMyArchiveItems(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(archiveService.listMyArchiveItems(principal, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ArchiveDetailDto> getArchiveItem(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        boolean isStaff = isStaffUser(principal);
        return ResponseEntity.ok(archiveService.getArchiveItem(id, isStaff));
    }

    @GetMapping("/{id}/file")
    public void streamFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean isStaff = isStaffUser(principal);
        archiveService.streamArchiveFile(id, isStaff, request, response);
    }

    @GetMapping("/{id}/download")
    public void downloadFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean isStaff = isStaffUser(principal);
        archiveService.downloadArchiveFile(id, isStaff, request, response);
    }

    @GetMapping("/{id}/thumbnail")
    public void getThumbnail(
            @PathVariable Long id,
            HttpServletRequest request,
            HttpServletResponse response) {
        archiveService.getArchiveThumbnail(id, request, response);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArchiveDetailDto> createArchiveItem(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") @Valid ArchiveMetadataRequest metadata,
            @AuthenticationPrincipal UserPrincipal principal) {
        ArchiveDetailDto created = archiveService.createArchiveItem(file, metadata, principal);
        return ResponseEntity.created(URI.create("/api/archive/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ArchiveDetailDto> updateArchiveItem(
            @PathVariable Long id,
            @Valid @RequestBody UpdateArchiveRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(archiveService.updateArchiveItem(id, request, principal));
    }

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
