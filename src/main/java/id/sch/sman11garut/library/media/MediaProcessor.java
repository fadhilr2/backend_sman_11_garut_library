package id.sch.sman11garut.library.media;

import id.sch.sman11garut.library.archive.ArchiveRepository;
import id.sch.sman11garut.library.archive.MediaType;
import id.sch.sman11garut.library.config.StorageProperties;
import id.sch.sman11garut.library.storage.FileStorageService;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MediaProcessor {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessor.class);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final int THUMBNAIL_SIZE = 400;

    private final ArchiveRepository archiveRepository;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    public MediaProcessor(
            ArchiveRepository archiveRepository,
            FileStorageService fileStorageService,
            StorageProperties storageProperties) {
        this.archiveRepository = archiveRepository;
        this.fileStorageService = fileStorageService;
        this.storageProperties = storageProperties;
    }

    @Async("mediaExecutor")
    @EventListener
    @Transactional
    public void onArchiveItemCreated(ArchiveItemCreatedEvent event) {
        try {
            Path sourcePath = fileStorageService.resolveSafePath(event.storedPath());

            if (event.mediaType() == MediaType.IMAGE) {
                processImage(event.itemId(), sourcePath);
            } else if (event.mediaType() == MediaType.VIDEO) {
                processVideo(event.itemId(), sourcePath);
            }
        } catch (Exception ex) {
            log.error("Media processing failed for archive item id {}: {}", event.itemId(), ex.getMessage(), ex);
        }
    }

    private void processImage(Long itemId, Path sourcePath) throws IOException {
        BufferedImage original = ImageIO.read(sourcePath.toFile());
        if (original == null) {
            log.warn("Could not read image for archive item id {}", itemId);
            return;
        }
        int width = original.getWidth();
        int height = original.getHeight();

        String relativeThumbnailPath = "thumbnails/" + YEAR_MONTH.format(YearMonth.now()) + "/" + UUID.randomUUID() + ".jpg";
        Path thumbnailAbsPath = storageProperties.root().resolve(relativeThumbnailPath);
        Files.createDirectories(thumbnailAbsPath.getParent());

        Thumbnails.of(sourcePath.toFile())
                .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                .keepAspectRatio(true)
                .outputFormat("jpg")
                .toFile(thumbnailAbsPath.toFile());

        archiveRepository.updateMediaMetadata(itemId, relativeThumbnailPath, width, height, null);
        log.info("Processed image thumbnail for archive item id {}", itemId);
    }

    private void processVideo(Long itemId, Path sourcePath) {
        if (!isCommandAvailable("ffprobe") || !isCommandAvailable("ffmpeg")) {
            log.info("ffmpeg/ffprobe not available, skipping video processing for archive item id {}", itemId);
            return;
        }

        try {
            // Extract duration, width, height with ffprobe
            ProcessBuilder probeBuilder = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_streams", sourcePath.toString());
            probeBuilder.redirectErrorStream(true);
            Process probeProcess = probeBuilder.start();
            String probeOutput = new String(probeProcess.getInputStream().readAllBytes());
            probeProcess.waitFor();

            Integer width = parseJsonInt(probeOutput, "\"width\"");
            Integer height = parseJsonInt(probeOutput, "\"height\"");
            Integer duration = parseJsonDuration(probeOutput);

            // Extract thumbnail frame at 1 second
            String relativeThumbnailPath = "thumbnails/" + YEAR_MONTH.format(YearMonth.now()) + "/" + UUID.randomUUID() + ".jpg";
            Path thumbnailAbsPath = storageProperties.root().resolve(relativeThumbnailPath);
            Files.createDirectories(thumbnailAbsPath.getParent());

            ProcessBuilder thumbBuilder = new ProcessBuilder(
                    "ffmpeg", "-ss", "00:00:01", "-i", sourcePath.toString(),
                    "-vframes", "1", "-q:v", "2", thumbnailAbsPath.toString());
            thumbBuilder.redirectErrorStream(true);
            Process thumbProcess = thumbBuilder.start();
            thumbProcess.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            thumbProcess.waitFor();

            if (Files.exists(thumbnailAbsPath)) {
                archiveRepository.updateMediaMetadata(itemId, relativeThumbnailPath, width, height, duration);
                log.info("Processed video thumbnail for archive item id {}", itemId);
            } else {
                archiveRepository.updateMediaMetadata(itemId, null, width, height, duration);
            }
        } catch (Exception ex) {
            log.error("Video processing failed for archive item id {}: {}", itemId, ex.getMessage(), ex);
        }
    }

    private boolean isCommandAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Integer parseJsonInt(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            int comma = json.indexOf(',', colon);
            int brace = json.indexOf('}', colon);
            int end = (comma > 0 && comma < brace) ? comma : brace;
            return Integer.parseInt(json.substring(colon + 1, end).trim().replace("\"", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseJsonDuration(String json) {
        try {
            String key = "\"duration\"";
            int idx = json.indexOf(key);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            int comma = json.indexOf(',', colon);
            int brace = json.indexOf('}', colon);
            int end = (comma > 0 && comma < brace) ? comma : brace;
            String val = json.substring(colon + 1, end).trim().replace("\"", "");
            return (int) Double.parseDouble(val);
        } catch (Exception e) {
            return null;
        }
    }
}
