package id.sch.sman11garut.library.media;

import id.sch.sman11garut.library.archive.ArchiveRepository;
import id.sch.sman11garut.library.archive.MediaType;
import id.sch.sman11garut.library.config.StorageProperties;
import id.sch.sman11garut.library.storage.FileStorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MediaProcessor {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessor.class);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final int THUMBNAIL_SIZE = 400;
    /** 50 megapixels: larger images get no thumbnail (decoding would need ~200 MB of heap). */
    static final long MAX_THUMBNAIL_PIXELS = 50_000_000L;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    private volatile Boolean ffmpegAvailable;

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

    // AFTER_COMMIT: the archive row must be committed before we update it from another thread.
    @Async("mediaExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        int[] size = readImageSize(sourcePath);
        if (size == null) {
            log.warn("Could not read image for archive item id {}", itemId);
            return;
        }
        int width = size[0];
        int height = size[1];
        // Decoding needs ~4 bytes per pixel; a huge (or crafted) image could exhaust the heap.
        if ((long) width * height > MAX_THUMBNAIL_PIXELS) {
            log.warn("Image for archive item id {} is {}x{}, too large for a thumbnail; using the placeholder",
                    itemId, width, height);
            archiveRepository.updateMediaMetadata(itemId, null, width, height, null);
            return;
        }

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

    /** Width and height from the image header, without decoding the pixels. */
    static int[] readImageSize(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    private void processVideo(Long itemId, Path sourcePath) {
        if (!ffmpegAvailable()) {
            log.info("ffmpeg/ffprobe not available, skipping video processing for archive item id {}", itemId);
            return;
        }

        Path probeOutput = null;
        try {
            // ffprobe output goes to a file, not a pipe, so the timeout always applies.
            probeOutput = Files.createTempFile("ffprobe-", ".json");
            boolean probed = run(List.of(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_streams", sourcePath.toString()), probeOutput);
            String probeJson = probed ? Files.readString(probeOutput) : "";

            Integer width = parseJsonInt(probeJson, "\"width\"");
            Integer height = parseJsonInt(probeJson, "\"height\"");
            Integer duration = parseJsonDuration(probeJson);

            // Extract thumbnail frame at 1 second
            String relativeThumbnailPath = "thumbnails/" + YEAR_MONTH.format(YearMonth.now()) + "/" + UUID.randomUUID() + ".jpg";
            Path thumbnailAbsPath = storageProperties.root().resolve(relativeThumbnailPath);
            Files.createDirectories(thumbnailAbsPath.getParent());

            boolean extracted = run(List.of(
                    "ffmpeg", "-ss", "00:00:01", "-i", sourcePath.toString(),
                    "-vframes", "1", "-q:v", "2", thumbnailAbsPath.toString()), null);

            if (extracted && Files.exists(thumbnailAbsPath)) {
                archiveRepository.updateMediaMetadata(itemId, relativeThumbnailPath, width, height, duration);
                log.info("Processed video thumbnail for archive item id {}", itemId);
            } else {
                Files.deleteIfExists(thumbnailAbsPath); // partial frame from a stopped ffmpeg
                archiveRepository.updateMediaMetadata(itemId, null, width, height, duration);
            }
        } catch (Exception ex) {
            log.error("Video processing failed for archive item id {}: {}", itemId, ex.getMessage(), ex);
        } finally {
            if (probeOutput != null) {
                try {
                    Files.deleteIfExists(probeOutput);
                } catch (IOException ignored) {
                    // OS temp folder; harmless if it stays.
                }
            }
        }
    }

    /**
     * Runs a command with {@link #PROCESS_TIMEOUT}. stdout+stderr go to {@code output}, or are
     * discarded when it is null. A process that doesn't finish in time is stopped.
     *
     * @return true when the process finished in time with exit code 0
     */
    private static boolean run(List<String> command, Path output) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.redirectOutput(output != null
                ? ProcessBuilder.Redirect.to(output.toFile())
                : ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.warn("{} did not finish within {} and was stopped", command.get(0), PROCESS_TIMEOUT);
            return false;
        }
        return process.exitValue() == 0;
    }

    /** Checked once per app start (installing ffmpeg later needs a restart). */
    private boolean ffmpegAvailable() {
        Boolean available = ffmpegAvailable;
        if (available == null) {
            available = isCommandAvailable("ffprobe") && isCommandAvailable("ffmpeg");
            ffmpegAvailable = available;
        }
        return available;
    }

    private static boolean isCommandAvailable(String command) {
        try {
            return run(List.of(command, "-version"), null);
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
