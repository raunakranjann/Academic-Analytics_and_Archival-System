package com.beu.result.AcademicAnalytics.controller;

import com.beu.result.AcademicAnalytics.entity.StudentInformations;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/")
public class AdminDashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminDashboardController.class);
    private final StudentInfoRepository studentRepository;

    public AdminDashboardController(StudentInfoRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @GetMapping
    public String renderDashboard(
            @RequestParam(name = "year", required = false) String year,
            @RequestParam(name = "branch", required = false) String branch,
            Model model) {

        long startTime = System.currentTimeMillis();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ADMIN"));
        model.addAttribute("isAdmin", isAdmin);

        String yearPrefix = normalizeYearInput(year);
        String branchParam = (branch != null && !branch.trim().isEmpty() && !branch.equals("All")) ? branch.trim() : null;

        List<StudentInformations> allStudents = studentRepository.findAll();
        
        List<StudentInformations> dataset = allStudents.stream()
            .filter(s -> yearPrefix == null || String.valueOf(s.getEffectiveSessionYear()).equals(yearPrefix))
            .filter(s -> branchParam == null || (s.getBranch() != null && s.getBranch().equalsIgnoreCase(branchParam)))
            .collect(Collectors.toList());

        LOG.info("Dashboard query fetched {} records. Filters: [Year={}, Branch={}]",
                dataset.size(), yearPrefix, branchParam);

        Map<String, Double> branchAvgCgpa = dataset.stream()
                .filter(s -> s.getBranch() != null && hasValidCgpa(s))
                .collect(Collectors.groupingBy(
                        StudentInformations::getBranch,
                        Collectors.averagingDouble(s -> safeParseDouble(s.getGrade().getCgpa()))
                ));

        int[] distributionBuckets = new int[5];
        for (StudentInformations s : dataset) {
            if (hasValidCgpa(s)) {
                double cgpa = safeParseDouble(s.getGrade().getCgpa());
                if (cgpa >= 9.0) distributionBuckets[4]++;
                else if (cgpa >= 8.0) distributionBuckets[3]++;
                else if (cgpa >= 7.0) distributionBuckets[2]++;
                else if (cgpa >= 6.0) distributionBuckets[1]++;
                else distributionBuckets[0]++;
            }
        }

        long passCount = dataset.stream()
                .filter(s -> hasValidCgpa(s) && safeParseDouble(s.getGrade().getCgpa()) >= 5.0)
                .count();
        long failCount = dataset.size() - passCount;

        double institutionalAverage = dataset.stream()
                .filter(this::hasValidCgpa)
                .mapToDouble(s -> safeParseDouble(s.getGrade().getCgpa()))
                .average().orElse(0.0);

        model.addAttribute("branches", studentRepository.findDistinctBranches());
        
        List<String> effectiveBatchYears = allStudents.stream()
                .map(s -> String.valueOf(s.getEffectiveSessionYear()))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        model.addAttribute("batchYears", effectiveBatchYears);

        model.addAttribute("selectedYear", year);
        model.addAttribute("selectedBranch", branch);

        model.addAttribute("branchLabels", branchAvgCgpa.keySet());
        model.addAttribute("branchValues", branchAvgCgpa.values());
        model.addAttribute("rangeData", distributionBuckets);
        model.addAttribute("passData", Arrays.asList(passCount, failCount));

        model.addAttribute("totalStudents", dataset.size());
        model.addAttribute("avgCollegeCgpa", institutionalAverage);

        LOG.debug("Dashboard rendering completed in {}ms", System.currentTimeMillis() - startTime);

        return "dashboard";
    }

    private String normalizeYearInput(String year) {
        if (year != null && !year.trim().isEmpty() && !year.equals("All")) {
            String trimmed = year.trim();
            return (trimmed.length() == 4) ? trimmed.substring(2) : trimmed;
        }
        return null;
    }

    private boolean hasValidCgpa(StudentInformations s) {
        return s.getGrade() != null && s.getGrade().getCgpa() != null && !s.getGrade().getCgpa().equals("NA");
    }

    private double safeParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return 0.0;
        }
    }
}
