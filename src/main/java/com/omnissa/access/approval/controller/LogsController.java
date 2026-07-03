package com.omnissa.access.approval.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Downloadable log bundle for troubleshooting: a ZIP containing the last
 * hour of application log lines from data/logs/ (see logback-spring.xml).
 * Authenticated like every other admin API endpoint.
 */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private static final Logger logger = LoggerFactory.getLogger(LogsController.class);

    private static final Path LOG_DIR = Paths.get("data", "logs");
    private static final Path CURRENT_LOG = LOG_DIR.resolve("app.log");

    // Log lines start with an ISO-8601 timestamp; only the first 19 chars
    // (yyyy-MM-dd'T'HH:mm:ss) are parsed — millis/offset variants don't matter.
    private static final int TIMESTAMP_LENGTH = 19;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @GetMapping("/bundle")
    public ResponseEntity<StreamingResponseBody> downloadBundle() {
        String filename = "approval-logs-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                + ".zip";

        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("logs-last-hour.log"));
                Writer writer = new java.io.OutputStreamWriter(zip, StandardCharsets.UTF_8);
                writeLastHour(writer);
                writer.flush();
                zip.closeEntry();
                zip.finish();
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.valueOf("application/zip"))
                .body(body);
    }

    private void writeLastHour(Writer writer) throws IOException {
        List<Path> sources = new ArrayList<>();

        // Newest rolled file first (older lines), if it was touched within
        // the window — the active file may have rolled mid-hour.
        Path rolled = newestRolledFile();
        if (rolled != null) {
            sources.add(rolled);
        }
        if (Files.isRegularFile(CURRENT_LOG)) {
            sources.add(CURRENT_LOG);
        }

        if (sources.isEmpty()) {
            writer.write("No log file found.\n");
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minus(1, ChronoUnit.HOURS);
        boolean wroteAny = false;
        for (Path source : sources) {
            wroteAny |= copyLinesWithinWindow(source, cutoff, writer);
        }
        if (!wroteAny) {
            writer.write("No log lines in the last hour (application idle since "
                    + "its last logged event).\n");
        }
    }

    private Path newestRolledFile() {
        if (!Files.isDirectory(LOG_DIR)) {
            return null;
        }
        long cutoffMillis = System.currentTimeMillis() - 60 * 60 * 1000L;
        try (Stream<Path> files = Files.list(LOG_DIR)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("app.") && name.endsWith(".log.gz");
                    })
                    .filter(p -> p.toFile().lastModified() >= cutoffMillis)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            logger.warn("Could not list rolled log files: {}", e.getMessage());
            return null;
        }
    }

    private boolean copyLinesWithinWindow(Path source, LocalDateTime cutoff, Writer writer)
            throws IOException {
        boolean withinWindow = false;
        boolean wroteAny = false;
        try (BufferedReader reader = openReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                LocalDateTime timestamp = parseLeadingTimestamp(line);
                if (timestamp != null) {
                    withinWindow = !timestamp.isBefore(cutoff);
                }
                // Continuation lines (stack traces etc.) follow the last
                // parsed timestamp's verdict.
                if (withinWindow) {
                    writer.write(line);
                    writer.write('\n');
                    wroteAny = true;
                }
            }
        }
        return wroteAny;
    }

    private BufferedReader openReader(Path source) throws IOException {
        InputStream in = Files.newInputStream(source);
        if (source.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private LocalDateTime parseLeadingTimestamp(String line) {
        if (line.length() < TIMESTAMP_LENGTH) {
            return null;
        }
        try {
            return LocalDateTime.parse(line.substring(0, TIMESTAMP_LENGTH), TIMESTAMP_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
