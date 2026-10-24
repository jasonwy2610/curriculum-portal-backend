package vn.edu.huflit.portal.academic.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vn.edu.huflit.portal.dto.request.AddAcademicAttemptRequest;
import vn.edu.huflit.portal.dto.response.AcademicRecordResponse;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.AcademicRecordService;
import vn.edu.huflit.portal.academic.service.GradeConversionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AcademicRecordServiceImpl implements AcademicRecordService {

    private final AcademicRecordRepository academicRecordRepository;
    private final GradeConversionService gradeConversionService;

    @Override
    public List<AcademicRecordResponse> getMyAcademicRecords(ObjectId studentId) {
        return academicRecordRepository.findByStudentId(studentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public AcademicRecordResponse getMyAcademicRecord(
            ObjectId studentId,
            String courseCode,
            ObjectId curriculumId
    ) {
        validateCourseCode(courseCode);

        return academicRecordRepository
                .findByStudentIdAndCourseCodeAndCurriculumId(
                        studentId, courseCode, curriculumId
                )
                .map(this::toResponse)
                .orElseThrow(() -> new RuntimeException(
                        "Không tìm thấy kết quả học tập của học phần " + courseCode
                ));
    }

    @Override
    public AcademicRecordResponse addAttempt(
            ObjectId studentId,
            String courseCode,
            ObjectId curriculumId,
            AddAcademicAttemptRequest request
    ) {
        validateCourseCode(courseCode);

        AcademicRecord record = academicRecordRepository
                .findByStudentIdAndCourseCodeAndCurriculumId(
                        studentId, courseCode, curriculumId
                )
                .orElseGet(() -> AcademicRecord.builder()
                        .studentId(studentId)
                        .courseCode(courseCode)
                        .curriculumId(curriculumId)
                        .attempts(new ArrayList<>())
                        .build());

        List<AcademicRecord.AttemptItem> attempts = record.getAttempts();
        if (attempts == null) {
            attempts = new ArrayList<>();
            record.setAttempts(attempts);
        }

        int nextAttemptNumber = attempts.stream()
                .map(AcademicRecord.AttemptItem::getAttemptNumber)
                .filter(number -> number != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        GradeConversionService.GradeResult grade =
                gradeConversionService.convert(request.getScore10());

        AcademicRecord.AttemptItem attempt = new AcademicRecord.AttemptItem();
        attempt.setSemesterCode(request.getSemesterCode());
        attempt.setScore10(request.getScore10());
        attempt.setLetterGrade(grade.letterGrade());
        attempt.setScore4(grade.score4());
        attempt.setAttemptNumber(
                request.getAttemptNumber() != null
                        ? request.getAttemptNumber()
                        : nextAttemptNumber
        );

        attempts.add(attempt);

        AcademicRecord.AttemptItem highestAttempt = attempts.stream()
                .filter(item -> item.getScore10() != null)
                .max(Comparator.comparing(AcademicRecord.AttemptItem::getScore10))
                .orElse(null);

        if (highestAttempt != null) {
            GradeConversionService.GradeResult highestGrade =
                    gradeConversionService.convert(highestAttempt.getScore10());

            record.setHighestScore10(highestAttempt.getScore10());
            record.setHighestLetterGrade(highestGrade.letterGrade());
            record.setHighestScore4(highestGrade.score4());
            record.setStatus(highestGrade.status());
        }

        academicRecordRepository.save(record);
        return toResponse(record);
    }

    private void validateCourseCode(String courseCode) {
        if (!StringUtils.hasText(courseCode)) {
            throw new RuntimeException("courseCode không được để trống");
        }
    }

    private AcademicRecordResponse toResponse(AcademicRecord record) {
        List<AcademicRecordResponse.AttemptResponse> attempts =
                record.getAttempts() == null
                        ? List.of()
                        : record.getAttempts().stream()
                        .map(attempt -> AcademicRecordResponse.AttemptResponse.builder()
                                .semesterCode(attempt.getSemesterCode())
                                .score10(attempt.getScore10())
                                .letterGrade(attempt.getLetterGrade())
                                .score4(attempt.getScore4())
                                .attemptNumber(attempt.getAttemptNumber())
                                .build())
                        .toList();

        return AcademicRecordResponse.builder()
                .id(record.getId() != null ? record.getId().toHexString() : null)
                .studentId(record.getStudentId() != null
                        ? record.getStudentId().toHexString() : null)
                .courseCode(record.getCourseCode())
                .curriculumId(record.getCurriculumId() != null
                        ? record.getCurriculumId().toHexString() : null)
                .attempts(attempts)
                .highestScore10(record.getHighestScore10())
                .highestLetterGrade(record.getHighestLetterGrade())
                .highestScore4(record.getHighestScore4())
                .status(record.getStatus())
                .build();
    }
}
