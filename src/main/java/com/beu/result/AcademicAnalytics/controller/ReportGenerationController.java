package com.beu.result.AcademicAnalytics.controller;

import com.beu.result.AcademicAnalytics.entity.StudentInformations;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/reports")
public class ReportGenerationController {

    private static final Logger LOG = LoggerFactory.getLogger(ReportGenerationController.class);
    private final StudentInfoRepository studentRepository;

    public ReportGenerationController(StudentInfoRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @GetMapping("/student-registry")
    public String viewStudentRegistry(
            @RequestParam(name = "year", required = false) String year,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "rank", required = false) Boolean rank,
            @RequestParam(name = "showBacklog", required = false) Boolean showBacklog,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Model model) {

        long startTime = System.currentTimeMillis();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ADMIN"));
        model.addAttribute("isAdmin", isAdmin);

        List<StudentInformations> filteredDataset = executeFilterQuery(year, branch, name, rank);

        // Calculate max completed semesters for each session
        Map<Integer, Integer> maxSemestersPerSession = filteredDataset.stream()
                .filter(s -> s.getGrade() != null)
                .collect(Collectors.groupingBy(
                        StudentInformations::getEffectiveSessionYear,
                        Collectors.collectingAndThen(
                                Collectors.maxBy((s1, s2) -> Integer.compare(s1.getGrade().getCompletedSemesterCount(), s2.getGrade().getCompletedSemesterCount())),
                                opt -> opt.map(s -> s.getGrade().getCompletedSemesterCount()).orElse(0)
                        )
                ));

        int totalItems = filteredDataset.size();
        int totalPages = (int) Math.ceil((double) totalItems / size);

        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        int startIdx = (page - 1) * size;
        int endIdx = Math.min(startIdx + size, totalItems);

        List<StudentInformations> pageContent = (startIdx >= totalItems) ? Collections.emptyList() : filteredDataset.subList(startIdx, endIdx);

        model.addAttribute("students", pageContent);
        model.addAttribute("branches", studentRepository.findDistinctBranches());
        model.addAttribute("batchYears", studentRepository.findDistinctBatchYears());
        model.addAttribute("selectedYear", year);
        model.addAttribute("selectedBranch", branch);
        model.addAttribute("selectedName", name);
        model.addAttribute("isRanked", rank);
        model.addAttribute("showBacklog", showBacklog != null && showBacklog);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalItems", totalItems);
        model.addAttribute("pageSize", size);
        model.addAttribute("maxSemestersPerSession", maxSemestersPerSession);

        LOG.debug("Registry view loaded. Page {}/{} ({}ms)", page, totalPages, System.currentTimeMillis() - startTime);

        return "student-registry";
    }

    @GetMapping("/export/excel")
    public void exportToExcel(
            @RequestParam(name = "year", required = false) String year,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "rank", required = false) Boolean rank,
            @RequestParam(defaultValue = "false") boolean showBacklog,
            HttpServletResponse response) throws IOException {

        LOG.info("Initiating Excel export. Filters: [Year={}, Branch={}, Rank={}, ShowBacklog={}]", year, branch, rank, showBacklog);
        List<StudentInformations> dataset = executeFilterQuery(year, branch, name, rank);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=academic_registry_export.xlsx");

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Academic Registry");

            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            Row headerRow = sheet.createRow(0);
            String[] columns = {"Reg No", "Student Name", "Branch", "Status", "Sem 1", "Sem 2", "Sem 3", "Sem 4", "Sem 5", "Sem 6", "Sem 7", "Sem 8", "CGPA"};

            for (int i = 0; i < columns.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (StudentInformations s : dataset) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(s.getRegistrationNumber());
                row.createCell(1).setCellValue(s.getStudentName());
                row.createCell(2).setCellValue(s.getBranch());
                row.createCell(3).setCellValue(s.getStudentStatus() != null ? s.getStudentStatus().name() : "REGULAR");

                if (s.getGrade() != null) {
                    if (showBacklog && s.getGrade().getBacklog() != null) {
                        row.createCell(4).setCellValue(s.getGrade().getBacklog().getSem1());
                        row.createCell(5).setCellValue(s.getGrade().getBacklog().getSem2());
                        row.createCell(6).setCellValue(s.getGrade().getBacklog().getSem3());
                        row.createCell(7).setCellValue(s.getGrade().getBacklog().getSem4());
                        row.createCell(8).setCellValue(s.getGrade().getBacklog().getSem5());
                        row.createCell(9).setCellValue(s.getGrade().getBacklog().getSem6());
                        row.createCell(10).setCellValue(s.getGrade().getBacklog().getSem7());
                        row.createCell(11).setCellValue(s.getGrade().getBacklog().getSem8());
                    } else {
                        row.createCell(4).setCellValue(s.getGrade().getSem1());
                        row.createCell(5).setCellValue(s.getGrade().getSem2());
                        row.createCell(6).setCellValue(s.getGrade().getSem3());
                        row.createCell(7).setCellValue(s.getGrade().getSem4());
                        row.createCell(8).setCellValue(s.getGrade().getSem5());
                        row.createCell(9).setCellValue(s.getGrade().getSem6());
                        row.createCell(10).setCellValue(s.getGrade().getSem7());
                        row.createCell(11).setCellValue(s.getGrade().getSem8());
                    }
                    row.createCell(12).setCellValue(s.getGrade().getCgpa());
                } else {
                    for (int j = 4; j <= 12; j++) {
                        row.createCell(j).setCellValue("-");
                    }
                }
            }

            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }

    private List<StudentInformations> executeFilterQuery(String year, String branch, String name, Boolean rank) {
        List<StudentInformations> allStudents = studentRepository.findAll();
        String yearPrefix = (year != null && !year.trim().isEmpty() && !year.equals("All")) ? year.trim() : null;
        String branchParam = (branch != null && !branch.trim().isEmpty() && !branch.equals("All")) ? branch.trim() : null;

        List<StudentInformations> filtered = allStudents.stream()
            .filter(s -> yearPrefix == null || String.valueOf(s.getEffectiveSessionYear()).equals(yearPrefix))
            .filter(s -> branchParam == null || (s.getBranch() != null && s.getBranch().equalsIgnoreCase(branchParam)))
            .filter(s -> name == null || (s.getStudentName() != null && s.getStudentName().toLowerCase().contains(name.toLowerCase())))
            .collect(Collectors.toList());

        if (Boolean.TRUE.equals(rank)) {
            filtered.sort((s1, s2) -> {
                Double cgpa1 = safeParseDouble(s1.getGrade() != null ? s1.getGrade().getCgpa() : "0");
                Double cgpa2 = safeParseDouble(s2.getGrade() != null ? s2.getGrade().getCgpa() : "0");
                return cgpa2.compareTo(cgpa1);
            });
        }
        return filtered;
    }

    private Double safeParseDouble(String val) {
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException | NullPointerException e) {
            return 0.0;
        }
    }
}
