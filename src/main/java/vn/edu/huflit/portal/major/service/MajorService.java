package vn.edu.huflit.portal.major.service;

import vn.edu.huflit.portal.major.dto.CreateMajorRequest;
import vn.edu.huflit.portal.major.dto.UpdateMajorRequest;
import vn.edu.huflit.portal.major.dto.MajorResponse;

import java.util.List;

public interface MajorService {

    List<MajorResponse> getAllMajors(String status, String keyword);

    MajorResponse getMajorById(String id);

    MajorResponse getMajorByCode(String code);

    MajorResponse createMajor(CreateMajorRequest request);

    MajorResponse updateMajor(String id, UpdateMajorRequest request);

    MajorResponse deleteMajor(String id);
}
