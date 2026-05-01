package com.beu.result.AcademicAnalytics.controller;

import com.beu.result.AcademicAnalytics.config.ResultSourceConfig;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import com.beu.result.AcademicAnalytics.service.DataSyncStatus;
import com.beu.result.AcademicAnalytics.service.TranscriptGenerationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Controller
public class ResultIngestionController {

    private final TranscriptGenerationService ingestionService;
    private final ResultSourceConfig sourceConfig;
    private final DataSyncStatus syncStatus;
    private final StudentInfoRepository studentInfoRepository;

    public ResultIngestionController(TranscriptGenerationService ingestionService,
                                     ResultSourceConfig sourceConfig,
                                     DataSyncStatus syncStatus,
                                     StudentInfoRepository studentInfoRepository) {
        this.ingestionService = ingestionService;
        this.sourceConfig = sourceConfig;
        this.syncStatus = syncStatus;
        this.studentInfoRepository = studentInfoRepository;
    }

    @GetMapping("/admin/ingestion-portal")
    public String showIngestionDashboard(Model model) {
        model.addAttribute("linkMap", sourceConfig.getAllLinks());
        return "ingestion-dashboard";
    }

    @GetMapping("/admin/ingest-by-session")
    public String showIngestBySession(Model model) {
        model.addAttribute("linkMap", sourceConfig.getAllLinks());
        model.addAttribute("batchYears", studentInfoRepository.findDistinctBatchYears());
        return "ingestion-by-session";
    }

    @PostMapping("/api/ingestion/start-session")
    public String initiateIngestionBySession(@RequestParam String sessionYear, @RequestParam String linkKey) {
        ingestionService.processResultSession(linkKey, sessionYear);
        // Corrected Redirect: Go back to the session page to see progress
        return "redirect:/admin/ingest-by-session";
    }

    @PostMapping("/api/ingestion/start-batch")
    @ResponseBody
    public Map<String, Object> initiateIngestionBatch(
            @RequestParam String linkKey,
            @RequestParam long startReg,
            @RequestParam long endReg
    ) {
        Map<String, Object> response = new HashMap<>();

        if (startReg > endReg) {
            response.put("status", "ERROR");
            response.put("message", "Start Registration cannot be greater than End Registration.");
            return response;
        }

        if (syncStatus.isJobActive()) {
            response.put("status", "BUSY");
            response.put("message", "A job is already running. Please wait.");
            return response;
        }

        ingestionService.processResultRange(linkKey, startReg, endReg);

        response.put("status", "BATCH_INITIATED");
        response.put("message", "Ingestion job queued for range: " + startReg + " - " + endReg);
        return response;
    }

    @GetMapping("/api/ingestion/progress")
    @ResponseBody
    public DataSyncStatus getIngestionTelemetry() {
        return syncStatus;
    }
}
