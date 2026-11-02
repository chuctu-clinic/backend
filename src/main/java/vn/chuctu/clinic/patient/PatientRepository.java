package vn.chuctu.clinic.patient;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    long countByUserId(Long userId);
}
