package vn.edu.huflit.portal.major.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.major.model.Major;

import java.util.List;
import java.util.Optional;

@Repository
public interface MajorRepository extends MongoRepository<Major, ObjectId> {
    Optional<Major> findByCode(String code);
    boolean existsByCode(String code);
    List<Major> findAllByStatus(String status);
}
