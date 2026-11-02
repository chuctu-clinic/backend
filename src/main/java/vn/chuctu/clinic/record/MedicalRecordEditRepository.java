package vn.chuctu.clinic.record;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicalRecordEditRepository extends JpaRepository<MedicalRecordEdit, Long> {

    /** Theo thứ tự đã lưu (id tăng dần). */
    List<MedicalRecordEdit> findByMedicalRecordIdInOrderByIdAsc(Collection<Long> recordIds);
}
