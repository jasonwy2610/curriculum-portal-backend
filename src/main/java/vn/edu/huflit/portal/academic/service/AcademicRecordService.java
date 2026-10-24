package vn.edu.huflit.portal.academic.service;

import org.bson.types.ObjectId;
import vn.edu.huflit.portal.dto.request.AddAcademicAttemptRequest;
import vn.edu.huflit.portal.dto.response.AcademicRecordResponse;

import java.util.List;

public interface AcademicRecordService {

    List<AcademicRecordResponse> getMyAcademicRecords(ObjectId studentId);

    AcademicRecordResponse getMyAcademicRecord(
            ObjectId studentId,
            String courseCode,
            ObjectId curriculumId
    );

    AcademicRecordResponse addAttempt(
            ObjectId studentId,
            String courseCode,
            ObjectId curriculumId,
            AddAcademicAttemptRequest request
    );
}
