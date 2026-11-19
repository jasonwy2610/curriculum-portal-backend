import {selectCurriculumTrack} from './curriculum-tracks.js';

export const requirementNames = {MANDATORY_COMMON: 'Bắt buộc chung', MANDATORY_SPECIALIZATION: 'Bắt buộc chuyên ngành',
  ELECTIVE_FREE: 'Tự chọn', ELECTIVE_SPECIALIZATION: 'Tự chọn', GRADUATION: 'Phương án tốt nghiệp', CONDITIONAL: 'Điều kiện bổ sung'};
export const programStatus = status => ({DRAFT: 'Bản nháp', ACTIVE: 'Đã công bố', ARCHIVED: 'Đã lưu trữ', INACTIVE: 'Ngừng áp dụng'})[status] ?? 'Chưa xác định';
export const canPublishTree = (program, dirty, validation) => program?.status === 'DRAFT' && !dirty && validation?.valid === true;
export const confirmTreeSwitch = (dirty, confirm) => !dirty || confirm('Có thay đổi chưa lưu. Bạn muốn bỏ thay đổi và chuyển cây?');

export function editableTrack(program, code) {
  const missing = Object.keys(program.specializationTrees ?? {}).length && !program.specializationTrees[code];
  const view = missing ? {semesters: [], requirementGroups: []} : selectCurriculumTrack(program, code);
  return structuredClone({semesters: (view.semesters ?? []).map(s => ({...s,
    courses: (s.courses ?? []).map(c => ({...c, specializationCodes: []}))})),
    requirementGroups: (view.requirementGroups ?? []).map(g => ({...g, specializationCodes: [code]}))});
}

export function copyTrack(source, sourceCode, target, targetCode) {
  if (target?.status !== 'DRAFT') throw new Error('Chỉ sao chép vào CTĐT bản nháp.');
  if (!target.specializations?.some(s => s.code === targetCode)) throw new Error('Chuyên ngành đích không thuộc CTĐT.');
  // A missing source tree is an error, not an empty configuration to overwrite the target with.
  selectCurriculumTrack(source, sourceCode);
  const tree = editableTrack(source, sourceCode);
  tree.requirementGroups.forEach(group => { group.specializationCodes = [targetCode]; });
  return tree;
}

export function courseRequirement(tree, code) {
  return (tree.requirementGroups ?? []).find(g => g.eligibleCourseCodes?.includes(code)
    || g.completionOptions?.some(o => o.courseCodes?.includes(code)));
}

export function groupedTree(tree) {
  return [...(tree.semesters ?? [])].sort((a, b) => a.semesterNumber - b.semesterNumber).map(s => ({...s,
    sections: Object.entries(requirementNames).filter(([kind]) => kind !== 'ELECTIVE_SPECIALIZATION')
      .map(([kind, label]) => ({kind, label, courses: (s.courses ?? []).filter(course => {
        const group = courseRequirement(tree, course.courseCode);
        return kind === 'ELECTIVE_FREE' ? group?.kind?.startsWith('ELECTIVE') : group?.kind === kind;
      })})).concat([{kind: 'UNASSIGNED', label: 'Chưa phân nhóm yêu cầu',
        courses: (s.courses ?? []).filter(c => !courseRequirement(tree, c.courseCode))}]).filter(g => g.courses.length)}));
}

export function treeWarnings(tree) {
  const warnings = [], seen = new Map();
  for (const s of tree.semesters ?? []) for (const c of s.courses ?? []) {
    if (seen.has(c.courseCode)) warnings.push(`Học kỳ ${s.semesterNumber} · ${c.courseCode}: trùng với học kỳ ${seen.get(c.courseCode)}.`);
    seen.set(c.courseCode, s.semesterNumber);
    if (!courseRequirement(tree, c.courseCode)) warnings.push(`Học kỳ ${s.semesterNumber} · ${c.courseCode}: chưa thuộc nhóm yêu cầu.`);
    if ((tree.requirementGroups ?? []).filter(g => g.eligibleCourseCodes?.includes(c.courseCode)
      || g.completionOptions?.some(o => o.courseCodes?.includes(c.courseCode))).length > 1)
      warnings.push(`Học kỳ ${s.semesterNumber} · ${c.courseCode}: xuất hiện trong nhiều nhóm yêu cầu.`);
  }
  if ((tree.requirementGroups ?? []).filter(g => g.kind?.startsWith('ELECTIVE')).length !== 1)
    warnings.push('Mỗi cây phải có đúng một nhóm tự chọn chung.');
  return warnings;
}

export function assignmentCandidates(programs, student) {
  if (!student) return [];
  return programs.filter(p => p.majorId === student.majorId && p.cohort === student.cohort
    && (student.curriculumId ? p.id === student.curriculumId : p.status === 'ACTIVE'));
}

export function assignmentPayload(student, program, code) {
  if (!student || !program || !code) throw new Error('Chọn sinh viên, CTĐT và chuyên ngành trước khi gán.');
  if (!assignmentCandidates([program], student).length || !program.specializations?.some(s => s.code === code))
    throw new Error('CTĐT hoặc chuyên ngành không phù hợp với ngành và khóa của sinh viên.');
  if (student.chosenSpecializationCode && student.chosenSpecializationCode !== code)
    throw new Error('Chuyên ngành đã được gán cố định.');
  if (Object.keys(program.specializationTrees ?? {}).length && !program.specializationTrees[code])
    throw new Error('CTĐT chưa có cây của chuyên ngành này.');
  return {curriculumId: program.id, chosenSpecializationCode: code};
}

export function assignTreeCourse(tree, code, groupCode) {
  const next = structuredClone(tree);
  const selected = next.requirementGroups?.find(g => g.groupCode === groupCode);
  if (!selected || next.requirementGroups.filter(g => g.groupCode === groupCode).length !== 1) return next;
  for (const group of next.requirementGroups) {
    group.eligibleCourseCodes = (group.eligibleCourseCodes ?? []).filter(c => c !== code);
    for (const option of group.completionOptions ?? []) option.courseCodes = (option.courseCodes ?? []).filter(c => c !== code);
  }
  if (selected.kind === 'GRADUATION') {
    if (!selected.completionOptions?.length) selected.completionOptions = [{optionCode: 'PA1', courseCodes: []}];
    selected.completionOptions[0].courseCodes.push(code);
  } else selected.eligibleCourseCodes.push(code);
  for (const semester of next.semesters ?? []) for (const course of semester.courses ?? [])
    if (course.courseCode === code) {
      course.type = selected.kind?.startsWith('ELECTIVE') ? 'ELECTIVE' : 'MANDATORY';
      course.requirementGroupCode = groupCode;
    }
  return next;
}

export function removeTreeCourse(tree, code) {
  const next = structuredClone(tree);
  next.semesters = (next.semesters ?? []).map(s => ({...s, courses: (s.courses ?? []).filter(c => c.courseCode !== code)}));
  for (const group of next.requirementGroups ?? []) {
    group.eligibleCourseCodes = (group.eligibleCourseCodes ?? []).filter(c => c !== code);
    for (const option of group.completionOptions ?? []) option.courseCodes = (option.courseCodes ?? []).filter(c => c !== code);
  }
  return next;
}

export function electiveProgress(data) {
  const group = (data?.groupProgress ?? []).find(g => g.kind?.startsWith('ELECTIVE'));
  if (!group || !Number.isFinite(group.requiredCredits) || !Number.isFinite(group.recognizedCredits)) return null;
  return {passed: data.electivePassedCredits ?? null, recognized: group.recognizedCredits, required: group.requiredCredits,
    missing: Math.max(0, group.requiredCredits - group.recognizedCredits), complete: group.complete};
}

export function treeValidationMessage(message, program) {
  let text = String(message);
  for (const spec of program?.specializations ?? []) text = text.replaceAll('[' + spec.code + ']', 'Chuyên ngành ' + spec.name);
  for (const [kind, name] of Object.entries(requirementNames)) text = text.replaceAll(kind, name);
  return text.replaceAll('Backend', 'Hệ thống').replaceAll('requiredCredits', 'chỉ tiêu tín chỉ').replaceAll('ELECTIVE', 'Tự chọn')
    .replaceAll('MANDATORY', 'Bắt buộc');
}
