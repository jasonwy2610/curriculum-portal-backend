export function selectCurriculumTrack(curriculum, code) {
  if (curriculum.specializations?.length && !curriculum.specializations.some(item => item?.code === code))
    throw new Error('Chuyên ngành cố định không thuộc CTĐT đã gán.');
  const explicit = Object.keys(curriculum.specializationTrees ?? {}).length > 0;
  const tree = explicit ? curriculum.specializationTrees[code] : curriculum;
  if (!tree) throw new Error('CTĐT thiếu cây của chuyên ngành đã gán.');
  const applies = codes => !codes?.length || codes.includes(code);
  const plan = curriculum.creditPlansBySpecialization?.[code];
  return {
    ...curriculum,
    semesters: (tree.semesters ?? []).map(semester => ({
      ...semester, courses: (semester.courses ?? []).filter(course => applies(course.specializationCodes))
    })),
    requirementGroups: (tree.requirementGroups ?? []).filter(group => applies(group.specializationCodes)),
    totalCredits: plan?.requiredTotal ?? curriculum.totalCredits,
    mandatoryCredits: plan?.mandatoryCredits ?? curriculum.mandatoryCredits
  };
}
export function curriculumCourseNeedsAttention(course) {
  return course.requiredForCompletion !== false && ['FAILED', 'NOT_STUDIED'].includes(course.status);
}

export function curriculumCourseStatus(course) {
  if (course.completedByReplacement) return 'Đạt qua thay thế';
  if (course.status === 'PASSED') return 'Đã đạt';
  if (course.electiveGroupComplete) return course.status === 'FAILED'
    ? 'Không đạt · Không bắt buộc hoàn thành thêm' : 'Không bắt buộc hoàn thành thêm';
  if (course.requiredForCompletion === false) return course.status === 'FAILED'
    ? 'Không đạt · có thể bù bằng môn khác' : 'Chưa học';
  return course.status === 'FAILED' ? 'Không đạt · Đang thiếu yêu cầu' : 'Chưa học · Đang thiếu yêu cầu';
}
