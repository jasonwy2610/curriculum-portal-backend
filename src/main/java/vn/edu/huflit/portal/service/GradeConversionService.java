package vn.edu.huflit.portal.service;

public interface GradeConversionService {

    GradeResult convert(double score10);

    record GradeResult(String letterGrade, double score4, String status) {
    }
}
