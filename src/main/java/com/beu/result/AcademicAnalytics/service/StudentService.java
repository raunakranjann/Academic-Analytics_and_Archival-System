package com.beu.result.AcademicAnalytics.service;

import com.beu.result.AcademicAnalytics.entity.StudentGrade;
import com.beu.result.AcademicAnalytics.entity.StudentInformations;
import com.beu.result.AcademicAnalytics.repository.StudentGradeRepository;
import com.beu.result.AcademicAnalytics.repository.StudentInfoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class StudentService {

    @Autowired
    private StudentInfoRepository studentInfoRepository;

    @Autowired
    private StudentGradeRepository studentGradeRepository;

    @Transactional
    public void saveStudent(StudentInformations studentDetails) {
        Optional<StudentInformations> existingStudentOpt = studentInfoRepository.findById(studentDetails.getRegistrationNumber());

        if (existingStudentOpt.isPresent()) {
            StudentInformations existingStudent = existingStudentOpt.get();
            existingStudent.setStudentName(studentDetails.getStudentName());
            existingStudent.setFatherName(studentDetails.getFatherName());
            existingStudent.setMotherName(studentDetails.getMotherName());
            existingStudent.setCourse(studentDetails.getCourse());
            existingStudent.setBranch(studentDetails.getBranch());
            existingStudent.setStudentStatus(studentDetails.getStudentStatus()); // Add this line

            if (studentDetails.getGrade() != null) {
                StudentGrade gradeDetails = studentDetails.getGrade();
                StudentGrade existingGrade = existingStudent.getGrade();
                if (existingGrade != null) {
                    existingGrade.setSem1(gradeDetails.getSem1());
                    existingGrade.setSem2(gradeDetails.getSem2());
                    existingGrade.setSem3(gradeDetails.getSem3());
                    existingGrade.setSem4(gradeDetails.getSem4());
                    existingGrade.setSem5(gradeDetails.getSem5());
                    existingGrade.setSem6(gradeDetails.getSem6());
                    existingStudent.getGrade().setCgpa(gradeDetails.getCgpa());
                    existingGrade.setSem7(gradeDetails.getSem7());
                    existingGrade.setSem8(gradeDetails.getSem8());
                    studentGradeRepository.save(existingGrade);
                }
            }
            studentInfoRepository.save(existingStudent);
        } else {
            StudentGrade grade = studentDetails.getGrade();
            if (grade != null) {
                grade.setStudentInformations(studentDetails);
                studentDetails.setGrade(grade);
            }
            studentInfoRepository.save(studentDetails);
        }
    }

    public Optional<StudentInformations> getStudentById(Long registrationNumber) {
        return studentInfoRepository.findById(registrationNumber);
    }
}
