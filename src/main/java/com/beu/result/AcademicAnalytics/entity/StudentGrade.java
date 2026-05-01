package com.beu.result.AcademicAnalytics.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.stream.Stream;

@Entity
@Table(name = "student_grades")
@Data
@NoArgsConstructor
public class StudentGrade {

    @Id
    private Long id;

    @OneToOne
    @MapsId
    @JoinColumn(name = "registration_number")
    @ToString.Exclude
    @JsonBackReference
    private StudentInformations studentInformations;

    @OneToOne(mappedBy = "studentGrade", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    @JsonManagedReference
    private StudentBacklog backlog;

    private String sem1;
    private String sem2;
    private String sem3;
    private String sem4;
    private String sem5;
    private String sem6;
    private String sem7;
    private String sem8;
    private String cgpa;

    public String getSem(int semester) {
        switch (semester) {
            case 1: return sem1; case 2: return sem2; case 3: return sem3;
            case 4: return sem4; case 5: return sem5; case 6: return sem6;
            case 7: return sem7; case 8: return sem8; default: return null;
        }
    }

    /**
     * Calculates the number of semesters with missing SGPA data.
     * A value is considered "missing" if it is null, empty, or "NA".
     */
    @Transient
    public int getMissingSemesterCount() {
        return (int) Stream.of(sem1, sem2, sem3, sem4, sem5, sem6, sem7, sem8)
                .filter(sgpa -> sgpa == null || sgpa.trim().isEmpty() || "NA".equalsIgnoreCase(sgpa.trim()))
                .count();
    }

    /**
     * Calculates the number of semesters with valid SGPA data.
     */
    @Transient
    public int getCompletedSemesterCount() {
        return 8 - getMissingSemesterCount();
    }
}
