package com.beu.result.DocumentArchival.controller;

import com.beu.result.AcademicAnalytics.config.ResultSourceConfig;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import com.beu.result.DocumentArchival.config.ArchivalJobRequest;
import com.beu.result.DocumentArchival.service.CertificateGenerationService;
import com.beu.result.DocumentArchival.util.ArchivalTelemetry;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/admin/archives")
public class ArchivalController {

    private static final Logger LOG = LoggerFactory.getLogger(ArchivalController.class);

    private final CertificateGenerationService archivalService;
    private final ArchivalTelemetry telemetry;
    private final ResultSourceConfig config;
    private final StudentInfoRepository studentInfoRepository;

    public ArchivalController(CertificateGenerationService archivalService,
                              ArchivalTelemetry telemetry,
                              ResultSourceConfig config,
                              StudentInfoRepository studentInfoRepository) {
        this.archivalService = archivalService;
        this.telemetry = telemetry;
        this.config = config;
        this.studentInfoRepository = studentInfoRepository;
    }

    private Path getStorageDirectory() {
        return Paths.get(System.getProperty("user.home"), "Documents", "Academic_Archives");
    }

    @GetMapping("/console")
    public String showArchivalConsole(Model model) {
        ArchivalJobRequest defaultRequest = new ArchivalJobRequest();
        defaultRequest.setStorageLocation(getStorageDirectory().toAbsolutePath().toString());
        model.addAttribute("linkMap", config.getAllLinks());
        model.addAttribute("jobRequest", defaultRequest);
        return "archival-console";
    }

    @GetMapping("/archive-by-session")
    public String showArchiveBySession(Model model) {
        model.addAttribute("linkMap", config.getAllLinks());
        model.addAttribute("batchYears", studentInfoRepository.findDistinctBatchYears());
        return "archive-by-session";
    }

    @PostMapping("/api/initiate-session")
    public ResponseEntity<Map<String, String>> initiateArchivalBySession(@RequestParam String sessionYear, @RequestParam String linkKey) {
        archivalService.generateCertificatesForSession(sessionYear, linkKey);
        Map<String, String> response = new HashMap<>();
        response.put("status", "JOB_INITIATED");
        response.put("message", "Archival process running for session: " + sessionYear);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/initiate")
    @ResponseBody
    public ResponseEntity<Map<String, String>> initiateArchivalJob(@ModelAttribute ArchivalJobRequest jobRequest) {
        String urlPattern = config.getUrl(jobRequest.getLinkKey());
        if (urlPattern == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid Data Source selected. Please try again."));
        }

        File storageDir = new File(jobRequest.getStorageLocation());
        if (!storageDir.exists()) {
            boolean created = storageDir.mkdirs();
            if (!created && !storageDir.exists()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not create storage directory. Check permissions."));
            }
        }

        if (jobRequest.getRangeStart() == null || jobRequest.getRangeEnd() == null ||
                jobRequest.getRangeStart() > jobRequest.getRangeEnd()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid Registration Number Range"));
        }

        archivalService.generateCertificates(jobRequest);

        Map<String, String> response = new HashMap<>();
        response.put("status", "JOB_INITIATED");
        response.put("message", "Archival process running for: " + jobRequest.getLinkKey());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/telemetry")
    @ResponseBody
    public ArchivalTelemetry getJobTelemetry() {
        return telemetry;
    }

    @GetMapping("/download/zip")
    public void downloadBatchZip(@RequestParam("batchName") String batchName,
                                 HttpServletResponse response) {
        String safeName = batchName.replaceAll("[^a-zA-Z0-9.-]", "_");
        Path subFolder = getStorageDirectory().resolve(safeName);
        String zipFilename = "Archive_Bundle_" + safeName + ".zip";
        String mergedFileToExclude = "Merged_Transcript_" + safeName + ".pdf";

        if (!Files.exists(subFolder)) {
            try { response.sendError(HttpServletResponse.SC_NOT_FOUND, "Batch folder not found"); } catch (IOException ignored) {}
            return;
        }

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilename + "\"");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream());
             Stream<Path> paths = Files.list(subFolder)) {

            paths.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase(mergedFileToExclude))
                    .forEach(path -> {
                        ZipEntry zipEntry = new ZipEntry(path.getFileName().toString());
                        try {
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            LOG.error("Failed to compress file: " + path, e);
                        }
                    });

            LOG.info("ZIP Archive downloaded from: {}", subFolder);

        } catch (IOException e) {
            LOG.error("ZIP Generation failed", e);
        }
    }

    @GetMapping("/download/merged")
    public ResponseEntity<FileSystemResource> downloadMergedPdf(@RequestParam("batchName") String batchName) {
        String safeName = batchName.replaceAll("[^a-zA-Z0-9.-]", "_");
        String filename = "Merged_Transcript_" + safeName + ".pdf";
        Path file = getStorageDirectory().resolve(safeName).resolve(filename);

        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        LOG.info("Merged PDF downloaded: {}", filename);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(new FileSystemResource(file));
    }
}
