package com.beu.result.DocumentArchival.service;

import com.beu.result.AcademicAnalytics.config.ResultSourceConfig;
import com.beu.result.AcademicAnalytics.entity.StudentInformations;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import com.beu.result.DocumentArchival.config.ArchivalJobRequest;
import com.beu.result.DocumentArchival.util.ArchivalTelemetry;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

@Service
public class CertificateGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateGenerationService.class);

    private final ArchivalTelemetry telemetry;
    private final ResultSourceConfig sourceConfig;
    private final StudentInfoRepository studentInfoRepository;

    public CertificateGenerationService(ArchivalTelemetry telemetry, ResultSourceConfig sourceConfig, StudentInfoRepository studentInfoRepository) {
        this.telemetry = telemetry;
        this.sourceConfig = sourceConfig;
        this.studentInfoRepository = studentInfoRepository;
    }

    @Async
    public void generateCertificatesForSession(String sessionYear, String linkKey) {
        List<Long> registrationNumbers = studentInfoRepository.findAll().stream()
                .filter(s -> String.valueOf(s.getEffectiveSessionYear()).equals(sessionYear))
                .map(StudentInformations::getRegistrationNumber)
                .collect(Collectors.toList());

        if (registrationNumbers.isEmpty()) {
            LOG.warn("No students found for session year: {}", sessionYear);
            telemetry.finalizeJob();
            return;
        }

        ArchivalJobRequest jobRequest = new ArchivalJobRequest();
        jobRequest.setLinkKey(linkKey);
        // The range is not used, but we set it for consistency
        jobRequest.setRangeStart(registrationNumbers.get(0));
        jobRequest.setRangeEnd(registrationNumbers.get(registrationNumbers.size() - 1));

        executeArchival(jobRequest, registrationNumbers);
    }

    @Async
    public void generateCertificates(ArchivalJobRequest jobRequest) {
        List<Long> registrationNumbers = LongStream.rangeClosed(jobRequest.getRangeStart(), jobRequest.getRangeEnd())
                .boxed().collect(Collectors.toList());
        executeArchival(jobRequest, registrationNumbers);
    }

    private void executeArchival(ArchivalJobRequest jobRequest, List<Long> registrationNumbers) {
        String urlTemplate = sourceConfig.getUrl(jobRequest.getLinkKey());
        if (urlTemplate == null) {
            LOG.error("ABORTING: No configuration found for Data Source '{}'", jobRequest.getLinkKey());
            return;
        }

        telemetry.initializeJob(registrationNumbers.size());

        String safeBatchName = jobRequest.getLinkKey().replaceAll("[^a-zA-Z0-9.-]", "_");
        File outputDir = Paths.get(jobRequest.getStorageLocation(), safeBatchName).toFile();
        if (!outputDir.exists()) outputDir.mkdirs();

        try (Playwright playwright = Playwright.create()) {
            String appPath = System.getProperty("user.dir");
            Path bundledPath = Paths.get(appPath, "browsers", "linux", "chrome-linux", "chrome");

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage", "--disable-gpu"));

            if (Files.exists(bundledPath)) {
                launchOptions.setExecutablePath(bundledPath);
                LOG.info("Archival Engine: Using bundled browser at {}", bundledPath);
            } else {
                LOG.warn("Bundled browser NOT found at {}. Attempting system default/download.", bundledPath);
            }

            try (Browser browser = playwright.chromium().launch(launchOptions);
                 BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 1024))) {

                Page page = context.newPage();

                for (long currentReg : registrationNumbers) {
                    processSingleRecord(page, urlTemplate, currentReg, outputDir.getAbsolutePath());
                }

                String mergedFileName = "Merged_Transcript_" + safeBatchName + ".pdf";
                LOG.info("Initiating Merge Sequence inside folder: {}", outputDir.getName());
                mergePdfArtifacts(outputDir.getAbsolutePath(), mergedFileName);
            }

        } catch (Exception e) {
            LOG.error("Critical Failure in Archival Engine", e);
        } finally {
            telemetry.finalizeJob();
            LOG.info("Archival Job Terminated.");
        }
    }

    private void processSingleRecord(Page page, String urlTemplate, long regNo, String outputDir) {
        String statusMessage;
        try {
            String targetUrl = urlTemplate.replace("{REG}", String.valueOf(regNo));

            page.navigate(targetUrl, new Page.NavigateOptions()
                    .setTimeout(45000)
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            PageStatus status = waitForDataCompleteness(page);

            if (status == PageStatus.READY) {
                if (page.locator("#container").count() > 0 && page.locator("app-root").count() == 0) {
                    page.waitForTimeout(500);
                }
                cleanLegacyUI(page);

                Path pdfPath = Paths.get(outputDir, regNo + ".pdf");
                printDynamicPdf(page, pdfPath);

                LOG.info("[Record {}] Archived Successfully.", regNo);
                statusMessage = "Archived: " + regNo;
            } else {
                if (status != PageStatus.NO_RECORD && status != PageStatus.EMPTY) {
                    captureErrorState(page, outputDir, regNo);
                }
                statusMessage = "Skipped (" + status + "): " + regNo;
            }
        } catch (Exception e) {
            LOG.error("[Record {}] Error: {}", regNo, e.getMessage());
            statusMessage = "Error: " + regNo;
            captureErrorState(page, outputDir, regNo);
        }
        telemetry.updateStatus(statusMessage);
    }

    private void cleanLegacyUI(Page page) {
        try {
            String script = """
            () => {
                const legacyContainer = document.querySelector('#container');
                const isModern = !!document.querySelector('app-root, app-result-three');
                
                if (legacyContainer && !isModern) {
                    const content = legacyContainer.cloneNode(true);
                    document.body.innerHTML = '';
                    
                    document.body.style.display = 'flex';
                    document.body.style.flexDirection = 'column';
                    document.body.style.alignItems = 'center';
                    document.body.style.backgroundColor = '#ffffff';
                    document.body.style.margin = '0';
                    document.body.style.padding = '10px';
                    document.body.style.height = 'auto';
                    document.body.appendChild(content);

                    content.style.position = 'static';
                    content.style.margin = '0 auto';
                    content.style.float = 'none';
                }
            }
            """;
            page.evaluate(script);
        } catch (Exception e) {
            LOG.warn("Legacy UI cleanup failed: {}", e.getMessage());
        }
    }

    private enum PageStatus { READY, EMPTY, NAME_EMPTY, NO_RECORD, LOADING }

    private PageStatus waitForDataCompleteness(Page page) {
        long startTime = System.currentTimeMillis();
        long timeout = 15000;

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                if (page.locator("text=/No Record Found|Invalid Registration|Data Not Available/i").count() > 0) {
                    return PageStatus.NO_RECORD;
                }

                Locator table = page.locator("table:has-text('Subject Code')").first();
                boolean tableHasData = table.count() > 0 && table.locator("tr").count() > 2;
                if (table.count() > 0 && !tableHasData) return PageStatus.EMPTY;

                boolean nameExists = false;
                Locator nameCell = page.locator("tr:has-text('Student Name') >> td").nth(1);
                if (nameCell.count() == 0) nameCell = page.locator("#ContentPlaceHolder1_DataList1_StudentNameLabel_0");

                if (nameCell.count() > 0) {
                    String nameText = nameCell.innerText().trim();
                    if (!nameText.isEmpty() && !nameText.equals("&nbsp;")) nameExists = true;
                    else return PageStatus.NAME_EMPTY;
                }

                if (nameExists && tableHasData) return PageStatus.READY;
                Thread.sleep(500);
            } catch (Exception e) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
        return PageStatus.LOADING;
    }

    private void printDynamicPdf(Page page, Path pdfPath) {
        @SuppressWarnings("unchecked")
        Map<String, String> dimMap = (Map<String, String>) page.evaluate("""
            () => ({ 
                width: Math.max(document.body.scrollWidth, document.documentElement.clientWidth) + 'px', 
                height: Math.max(document.body.scrollHeight, document.documentElement.clientHeight) + 'px' 
            })
        """);
        page.addStyleTag(new Page.AddStyleTagOptions().setContent("@media print { body { margin: 0; padding: 10px; } @page { margin: 0; } }"));
        page.pdf(new Page.PdfOptions().setPath(pdfPath).setWidth(dimMap.get("width")).setHeight(dimMap.get("height")).setPrintBackground(true));
    }

    private void mergePdfArtifacts(String folderPath, String outputFileName) {
        try {
            PDFMergerUtility pdfMerger = new PDFMergerUtility();
            pdfMerger.setDestinationFileName(folderPath + File.separator + outputFileName);
            File folder = new File(folderPath);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".pdf") && !name.startsWith("ERROR_"));
            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) pdfMerger.addSource(file);
                pdfMerger.mergeDocuments(null);
                LOG.info("Merged Document Created: {}", outputFileName);
            }
        } catch (IOException e) {
            LOG.error("Failed to merge PDF artifacts", e);
        }
    }

    private void captureErrorState(Page page, String outputDir, long regNo) {
        try {
            Path errorShot = Paths.get(outputDir, "ERROR_" + regNo + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(errorShot));
        } catch (Exception ignored) {}
    }
}
