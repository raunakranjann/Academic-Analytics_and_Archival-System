package com.beu.result.AcademicAnalytics.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "student_informations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentInformations {

    @Id
    private Long registrationNumber;

    private String studentName;
    private String fatherName;
    private String motherName;
    private String course;
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(255) default 'REGULAR'")
    private StudentStatus studentStatus = StudentStatus.REGULAR;

    @OneToOne(mappedBy = "studentInformations", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    @JsonManagedReference
    private StudentGrade grade;

    // Ingestion Constructor
    public StudentInformations(Long registrationNumber, String studentName, String fatherName,
                               String motherName, String course, String branch) {
        this.registrationNumber = registrationNumber;
        this.studentName = studentName;
        this.fatherName = fatherName;
        this.motherName = motherName;
        this.course = course;
        this.branch = branch;
    }

    /**
     * Calculates the effective session year for a student based on their status.
     */
    @Transient
    public int getEffectiveSessionYear() {
        try {
            int registrationYear = Integer.parseInt(String.valueOf(this.registrationNumber).substring(0, 2));
            switch (this.studentStatus) {
                case LATERAL_ENTRY:
                    return registrationYear - 1;
                case YEAR_BACK:
                    return registrationYear + 1;
                default: // REGULAR
                    return registrationYear;
            }
        } catch (Exception e) {
            return 0; // Fallback for invalid registration number format
        }
    }
}
