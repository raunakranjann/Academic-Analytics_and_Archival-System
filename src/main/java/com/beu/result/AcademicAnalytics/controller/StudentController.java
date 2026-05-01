package com.beu.result.AcademicAnalytics.controller;

import com.beu.result.AcademicAnalytics.entity.StudentGrade;
import com.beu.result.AcademicAnalytics.entity.StudentInformations;
import com.beu.result.AcademicAnalytics.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Controller
@RequestMapping("/student")
public class StudentController {

    @Autowired
    private StudentService studentService;

    @GetMapping("/form")
    public String showStudentForm(@RequestParam(name = "id", required = false) Long id, Model model) {
        StudentInformations student;
        if (id != null) {
            Optional<StudentInformations> studentOpt = studentService.getStudentById(id);
            if (studentOpt.isPresent()) {
                student = studentOpt.get();
                if (student.getGrade() == null) {
                    student.setGrade(new StudentGrade());
                }
            } else {
                student = new StudentInformations();
                student.setGrade(new StudentGrade());
            }
        } else {
            student = new StudentInformations();
            student.setGrade(new StudentGrade());
        }
        model.addAttribute("student", student);
        return "student-form";
    }

    @PostMapping("/save")
    public String saveStudent(@ModelAttribute StudentInformations student) {
        studentService.saveStudent(student);
        return "redirect:/reports/student-registry";
    }

    @GetMapping("/api/{id}")
    @ResponseBody
    public ResponseEntity<StudentInformations> getStudentForApi(@PathVariable Long id) {
        Optional<StudentInformations> studentOpt = studentService.getStudentById(id);
        return studentOpt.map(ResponseEntity::ok)
                         .orElse(ResponseEntity.notFound().build());
    }

    // Fixed authorization to allow both ADMIN and API roles to access the data endpoint
    @GetMapping("/api/data/{registrationNumber}")
    @PreAuthorize("hasAnyAuthority('API', 'ADMIN')")
    @ResponseBody
    public ResponseEntity<StudentInformations> getStudentData(@PathVariable Long registrationNumber) {
        Optional<StudentInformations> studentOpt = studentService.getStudentById(registrationNumber);
        return studentOpt.map(ResponseEntity::ok)
                         .orElse(ResponseEntity.notFound().build());
    }
}
