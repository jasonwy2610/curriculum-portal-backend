package vn.edu.huflit.portal.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.entity.Major;

import java.util.Optional;

@Repository
public interface MajorRepository extends MongoRepository<Major, ObjectId> {
    Optional<Major> findByCode(String code);
}