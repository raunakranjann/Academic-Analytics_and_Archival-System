package com.beu.result.AcademicAnalytics.service;

import com.beu.result.AcademicAnalytics.config.ResultSourceConfig;
import com.beu.result.AcademicAnalytics.entity.StudentBacklog;
import com.beu.result.AcademicAnalytics.entity.StudentGrade;
import com.beu.result.AcademicAnalytics.entity.StudentInformations;
import com.beu.result.AcademicAnalytics.repository.StudentBacklogRepository;
import com.beu.result.AcademicAnalytics.repository.StudentGradeRepository;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

@Service
public class TranscriptGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptGenerationService.class);

    private final StudentInfoRepository studentInfoRepository;
    private final StudentGradeRepository gradeRepository;
    private final StudentBacklogRepository backlogRepository;
    private final DataSyncStatus syncStatus;
    private final ResultSourceConfig sourceConfig;

    public TranscriptGenerationService(StudentInfoRepository studentInfoRepository,
                                       StudentGradeRepository gradeRepository,
                                       StudentBacklogRepository backlogRepository,
                                       DataSyncStatus syncStatus,
                                       ResultSourceConfig sourceConfig) {
        this.studentInfoRepository = studentInfoRepository;
        this.gradeRepository = gradeRepository;
        this.backlogRepository = backlogRepository;
        this.syncStatus = syncStatus;
        this.sourceConfig = sourceConfig;
    }

    @Async
    @Transactional
    public void processResultSession(String linkKey, String sessionYear) {
        List<Long> registrationNumbers = studentInfoRepository.findAll().stream()
                .filter(s -> String.valueOf(s.getEffectiveSessionYear()).equals(sessionYear))
                .map(StudentInformations::getRegistrationNumber)
                .collect(Collectors.toList());

        if (registrationNumbers.isEmpty()) {
            LOG.warn("No students found for session year: {}", sessionYear);
            syncStatus.finishJob();
            return;
        }
        
        executeIngestion(linkKey, registrationNumbers);
    }

    @Async
    @Transactional
    public void processResultRange(String linkKey, long startReg, long endReg) {
        List<Long> registrationNumbers = LongStream.rangeClosed(startReg, endReg).boxed().collect(Collectors.toList());
        executeIngestion(linkKey, registrationNumbers);
    }

    private void executeIngestion(String linkKey, List<Long> registrationNumbers) {
        String urlPattern = linkKey.startsWith("http") ? linkKey : sourceConfig.getUrl(linkKey);
        if (urlPattern == null) {
            LOG.error("Ingestion Aborted: Invalid Link Key or URL '{}'", linkKey);
            return;
        }

        syncStatus.startJob(registrationNumbers.size());

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage", "--disable-gpu"));

            try (Browser browser = playwright.chromium().launch(launchOptions);
                 BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 720))) {

                Page page = context.newPage();

                for (Long regNo : registrationNumbers) {
                    String logMessage = "Ingesting: " + regNo;
                    try {
                        String targetUrl = urlPattern.replace("{REG}", String.valueOf(regNo));
                        page.navigate(targetUrl, new Page.NavigateOptions().setTimeout(60000).setWaitUntil(WaitUntilState.NETWORKIDLE));

                        PageStatus status = checkPageStatus(page);
                        if (status == PageStatus.READY) {
                            boolean isLegacy = page.locator("#ContentPlaceHolder1_GridView3").count() > 0;
                            String curSem = isLegacy ? extractSemesterLegacy(page) : extractSemesterModern(page);

                            StudentInformations parsedProfile = isLegacy ? parseLegacyPortalProfile(page, regNo) : parseModernPortalProfile(page, regNo);
                            if (parsedProfile != null) {
                                saveOrUpdateStudentInformation(parsedProfile);
                                StudentGrade grades = isLegacy ? parseLegacyPortalGrades(page, regNo) : parseModernPortalGrades(page, regNo);
                                if (grades != null) synchronizeGrades(grades, regNo, curSem);
                                String remarks = isLegacy ? extractRemarksLegacy(page) : extractRemarksModern(page);
                                if (curSem != null) synchronizeBacklogs(regNo, curSem, remarks);
                                logMessage = "Indexed: " + parsedProfile.getStudentName();
                                LOG.info(logMessage);
                            } else {
                                logMessage = "Skipped (Parse Error): " + regNo;
                            }
                        } else {
                            logMessage = "Skipped (" + status + "): " + regNo;
                        }
                    } catch (Exception e) {
                        logMessage = "Error: " + e.getMessage();
                        LOG.error("Failed to ingest {}", regNo, e);
                    } finally {
                        syncStatus.updateProgress(logMessage);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Critical Failure in Ingestion Engine", e);
        } finally {
            syncStatus.finishJob();
        }
    }

    private void saveOrUpdateStudentInformation(StudentInformations parsedProfile) {
        Optional<StudentInformations> existingStudentOpt = studentInfoRepository.findById(parsedProfile.getRegistrationNumber());

        if (existingStudentOpt.isPresent()) {
            StudentInformations existingStudent = existingStudentOpt.get();
            
            // Preserve manually entered names. Only update if the DB field is blank.
            if (existingStudent.getStudentName() == null || existingStudent.getStudentName().trim().isEmpty()) {
                existingStudent.setStudentName(parsedProfile.getStudentName());
            }
            if (existingStudent.getFatherName() == null || existingStudent.getFatherName().trim().isEmpty()) {
                existingStudent.setFatherName(parsedProfile.getFatherName());
            }
            if (existingStudent.getMotherName() == null || existingStudent.getMotherName().trim().isEmpty()) {
                existingStudent.setMotherName(parsedProfile.getMotherName());
            }

            // Always update course and branch as they are derived from the registration number
            existingStudent.setCourse(parsedProfile.getCourse());
            existingStudent.setBranch(parsedProfile.getBranch());
            
            studentInfoRepository.save(existingStudent);
        } else {
            // New student, save all parsed information
            studentInfoRepository.save(parsedProfile);
        }
    }

    private enum PageStatus { READY, EMPTY_TABLE, NAME_EMPTY, NO_RECORD, UNKNOWN }

    private PageStatus checkPageStatus(Page page) {
        if (page.locator("text=/No Record Found|Invalid Registration|Data Not Available/i").count() > 0) {
            return PageStatus.NO_RECORD;
        }

        Locator nameCell = page.locator("tr:has-text('Student Name') >> td").nth(1);
        if (nameCell.count() == 0) {
            nameCell = page.locator("#ContentPlaceHolder1_DataList1_StudentNameLabel_0");
        }

        if (nameCell.count() > 0) {
            String nameValue = nameCell.innerText().trim();
            if (nameValue.isEmpty() || nameValue.equals("&nbsp;")) {
                return PageStatus.NAME_EMPTY;
            }
        } else {
            return PageStatus.UNKNOWN;
        }

        Locator table = page.locator("table:has-text('Subject Code')").first();
        if (table.count() > 0) {
            int rows = table.locator("tr").count();
            return (rows > 2) ? PageStatus.READY : PageStatus.EMPTY_TABLE;
        }
        return PageStatus.UNKNOWN;
    }

    private void synchronizeGrades(StudentGrade newGrades, Long regNo, String incomingSem) {
        Optional<StudentGrade> existingOpt = gradeRepository.findById(regNo);
        StudentGrade target;

        if (existingOpt.isPresent()) {
            target = existingOpt.get();
            target.setSem1(resolveHigherScore(target.getSem1(), newGrades.getSem1()));
            target.setSem2(resolveHigherScore(target.getSem2(), newGrades.getSem2()));
            target.setSem3(resolveHigherScore(target.getSem3(), newGrades.getSem3()));
            target.setSem4(resolveHigherScore(target.getSem4(), newGrades.getSem4()));
            target.setSem5(resolveHigherScore(target.getSem5(), newGrades.getSem5()));
            target.setSem6(resolveHigherScore(target.getSem6(), newGrades.getSem6()));
            target.setSem7(resolveHigherScore(target.getSem7(), newGrades.getSem7()));
            target.setSem8(resolveHigherScore(target.getSem8(), newGrades.getSem8()));

            int dbMaxSem = calculateMaxSem(target);
            int incomingSemIndex = getSemesterOrder(incomingSem);

            if (incomingSemIndex >= dbMaxSem && newGrades.getCgpa() != null && !newGrades.getCgpa().equals("NA")) {
                target.setCgpa(newGrades.getCgpa());
            }
        } else {
            target = newGrades;
            target.setStudentInformations(studentInfoRepository.getReferenceById(regNo));
        }
        gradeRepository.save(target);
    }

    private int calculateMaxSem(StudentGrade g) {
        if (isVal(g.getSem8())) return 8;
        if (isVal(g.getSem7())) return 7;
        if (isVal(g.getSem6())) return 6;
        if (isVal(g.getSem5())) return 5;
        if (isVal(g.getSem4())) return 4;
        if (isVal(g.getSem3())) return 3;
        if (isVal(g.getSem2())) return 2;
        if (isVal(g.getSem1())) return 1;
        return 0;
    }

    private boolean isVal(String s) { return s != null && !s.equals("NA"); }

    private void synchronizeBacklogs(Long regNo, String semester, String remarks) {
        String valueToSave = (remarks == null || remarks.isEmpty()) ? "PASS" : remarks;
        String semClean = semester.toUpperCase().replaceAll("[^0-9IVX]", "");

        Optional<StudentBacklog> existingOpt = backlogRepository.findById(regNo);
        StudentBacklog backlog = existingOpt.orElse(new StudentBacklog());

        if (existingOpt.isEmpty()) {
            backlog.setStudentGrade(gradeRepository.getReferenceById(regNo));
        }

        switch (semClean) {
            case "1", "I" -> backlog.setSem1(valueToSave);
            case "2", "II" -> backlog.setSem2(valueToSave);
            case "3", "III" -> backlog.setSem3(valueToSave);
            case "4", "IV" -> backlog.setSem4(valueToSave);
            case "5", "V" -> backlog.setSem5(valueToSave);
            case "6", "VI" -> backlog.setSem6(valueToSave);
            case "7", "VII" -> backlog.setSem7(valueToSave);
            case "8", "VIII" -> backlog.setSem8(valueToSave);
        }
        backlogRepository.save(backlog);
    }

    private StudentInformations parseModernPortalProfile(Page page, long regNo) {
        try {
            String name = page.locator("tr:has-text('Student Name') >> td").nth(1).innerText().trim();
            if (name.isEmpty()) return null;
            String father = page.locator("tr:has-text('Father') >> td").nth(1).innerText().trim();
            Locator motherCell = page.locator("tr:has-text('Mother') >> td").nth(1);
            String mother = motherCell.count() > 0 ? motherCell.innerText().trim() : page.locator("tr:has-text('Father') >> td").nth(3).innerText().trim();
            String branch = page.locator("tr:has-text('Course Name') >> td").nth(1).innerText().replaceAll("^\\d+\\s*-\\s*", "").trim();
            return new StudentInformations(regNo, name, father, mother, "B.Tech", branch);
        } catch (Exception e) { return null; }
    }

    private StudentGrade parseModernPortalGrades(Page page, long regNo) {
        try {
            Locator sgpaRow = page.locator("tr:has-text('SGPA')").last();
            if (sgpaRow.count() == 0) return null;
            List<String> cells = sgpaRow.locator("td, th").allInnerTexts();
            StudentGrade g = new StudentGrade();
            g.setSem1(normalize(cells, 1)); g.setSem2(normalize(cells, 2));
            g.setSem3(normalize(cells, 3)); g.setSem4(normalize(cells, 4));
            g.setSem5(normalize(cells, 5)); g.setSem6(normalize(cells, 6));
            g.setSem7(normalize(cells, 7));
            g.setSem8(normalize(cells, 8));
            g.setCgpa(normalize(cells, 9));
            return g;
        } catch (Exception e) { return null; }
    }

    private StudentInformations parseLegacyPortalProfile(Page page, long regNo) {
        try {
            String name = getTextById(page, "#ContentPlaceHolder1_DataList1_StudentNameLabel_0");
            if (name == null || name.isEmpty()) return null;
            String father = getTextById(page, "#ContentPlaceHolder1_DataList1_FatherNameLabel_0");
            String mother = getTextById(page, "#ContentPlaceHolder1_DataList1_MotherNameLabel_0");
            String branch = getTextById(page, "#ContentPlaceHolder1_DataList1_CourseLabel_0");
            return new StudentInformations(regNo, name, father, mother, "B.Tech", branch);
        } catch (Exception e) { return null; }
    }

    private StudentGrade parseLegacyPortalGrades(Page page, long regNo) {
        try {
            Locator row = page.locator("#ContentPlaceHolder1_GridView3 tr").nth(1);
            if (row.count() == 0) return null;
            List<String> cells = row.locator("td").allInnerTexts();
            StudentGrade g = new StudentGrade();
            g.setSem1(normalize(cells, 0)); g.setSem2(normalize(cells, 1));
            g.setSem3(normalize(cells, 2)); g.setSem4(normalize(cells, 3));
            g.setSem5(normalize(cells, 4)); g.setSem6(normalize(cells, 5));
            g.setSem7(normalize(cells, 6));
            g.setSem8(normalize(cells, 7));
            g.setCgpa(normalize(cells, 8));
            return g;
        } catch (Exception e) { return null; }
    }

    private String extractSemesterModern(Page page) {
        try {
            Locator semCell = page.locator("td:has-text('Semester:')").first();
            if (semCell.count() > 0) return semCell.innerText().replace("Semester:", "").trim();
            Matcher m = Pattern.compile("(?i)(?:semester|sem)=([IVX0-9]+)").matcher(page.url());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    private String extractRemarksModern(Page page) {
        try {
            if (page.locator("span.text-danger:has-text('FAIL')").count() > 0) return "FAIL";
            if (page.locator("span.text-danger:has-text('ABSENT')").count() > 0) return "ABSENT";
            return "PASS";
        } catch (Exception e) { return "PASS"; }
    }

    private String extractSemesterLegacy(Page page) {
        try {
            Locator semLoc = page.locator("#ContentPlaceHolder1_DataList2_Exam_Name_0");
            return (semLoc.count() > 0) ? semLoc.innerText().trim() : null;
        } catch (Exception e) { return null; }
    }

    private String extractRemarksLegacy(Page page) {
        try {
            String val = getTextById(page, "#ContentPlaceHolder1_DataList3_remarkLabel_0");
            return (val != null && !val.isEmpty()) ? val : "PASS";
        } catch (Exception e) { return "PASS"; }
    }

    private String resolveHigherScore(String oldVal, String newVal) {
        if (newVal == null || newVal.equals("NA") || newVal.equals("-")) return oldVal;
        if (oldVal == null || oldVal.equals("NA") || oldVal.equals("-")) return newVal;
        try { return (Double.parseDouble(newVal) >= Double.parseDouble(oldVal)) ? newVal : oldVal; }
        catch (Exception e) { return newVal; }
    }

    private String normalize(List<String> cells, int index) {
        if (index < cells.size()) {
            String val = cells.get(index).trim();
            return (val.equals("-") || val.equals("NA") || val.isEmpty()) ? "NA" : val;
        } return "NA";
    }

    private String getTextById(Page page, String selector) {
        Locator loc = page.locator(selector);
        return (loc.count() > 0) ? loc.innerText().trim() : null;
    }

    private int getSemesterOrder(String sem) {
        if (sem == null) return 0;
        return switch (sem.trim().toUpperCase()) {
            case "I", "1" -> 1; case "II", "2" -> 2; case "III", "3" -> 3;
            case "IV", "4" -> 4; case "V", "5" -> 5; case "VI", "6" -> 6;
            case "VII", "7" -> 7; case "VIII", "8" -> 8; default -> 0;
        };
    }
}
