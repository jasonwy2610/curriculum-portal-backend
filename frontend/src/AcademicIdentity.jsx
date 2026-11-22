import React from 'react';

export default function AcademicIdentity({student, curriculum, majors = []}) {
  const major = majors.find(m => m.id === (student?.majorId ?? curriculum?.majorId));
  const code = student?.chosenSpecializationCode ?? curriculum?.specializationCode;
  const specialization = (curriculum?.specializations ?? major?.specializations ?? []).find(s => s.code === code);
  return <dl className="academic-identity">
    <div><dt>Ngành đào tạo</dt><dd>{major?.name ?? 'Chưa có thông tin ngành'}</dd></div>
    <div><dt>Chuyên ngành cố định</dt><dd>{specialization?.name ?? (code ? 'Chưa có tên chuyên ngành' : 'Chưa được gán')}</dd></div>
    <div><dt>Khóa tuyển sinh</dt><dd>{student?.cohort ?? curriculum?.cohort ?? 'Chưa có thông tin'}</dd></div>
    <div><dt>CTĐT đã lưu</dt><dd>{curriculum?.curriculumName ?? (student?.curriculumId ? 'Đang tải CTĐT đã lưu' : 'Hồ sơ chưa có CTĐT')}</dd></div>
    <div><dt>Phiên bản cố định</dt><dd>{curriculum?.version ?? 'Chưa có thông tin'}</dd></div>
  </dl>;
}
