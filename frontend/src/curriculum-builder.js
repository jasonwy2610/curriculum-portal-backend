import {assignTreeCourse, treeWarnings} from './curriculum-ui.js';

export const treeCourses = tree => (tree?.semesters ?? []).flatMap(s => (s.courses ?? []).map(c => ({...c, semesterNumber: s.semesterNumber})));
export function emptyTree(code) {
  return {semesters: [{semesterNumber: 1, courses: []}], requirementGroups: [
    ['COMMON', 'Bắt buộc chung', 'MANDATORY_COMMON'], ['SPECIAL', 'Bắt buộc chuyên ngành', 'MANDATORY_SPECIALIZATION'],
    ['FREE', 'Tự chọn chung', 'ELECTIVE_FREE']].map(([groupCode, groupName, kind]) =>
      ({groupCode, groupName, kind, specializationCodes: [code], requiredCredits: 0, eligibleCourseCodes: [], completionOptions: []}))};
}
export function addSemester(tree, number) {
  if (!Number.isInteger(number) || number < 1) throw new Error('Học kỳ phải là số nguyên dương.');
  const next = structuredClone(tree);
  if (!next.semesters.some(s => s.semesterNumber === number)) next.semesters.push({semesterNumber: number, courses: []});
  next.semesters.sort((a,b) => a.semesterNumber - b.semesterNumber);
  return next;
}
export function deriveMandatory(tree, catalog) {
  const next = structuredClone(tree), credits = new Map(catalog.map(c => [c.courseCode, c.credits]));
  for (const group of next.requirementGroups ?? []) if (group.kind?.startsWith('MANDATORY'))
    group.requiredCredits = [...new Set(group.eligibleCourseCodes ?? [])].reduce((sum, code) => sum + (credits.get(code) ?? 0), 0);
  return next;
}
export function addCourses(tree, semester, codes, catalog, groupCode) {
  let next = addSemester(tree, semester);
  const seen = new Set(treeCourses(next).map(c => c.courseCode));
  for (const code of codes) {
    if (seen.has(code)) continue;
    const course = catalog.find(c => c.courseCode === code);
    if (!course) throw new Error('Học phần không có trong danh mục: ' + code);
    next.semesters.find(s => s.semesterNumber === semester).courses.push({courseCode: code, courseName: course.courseName,
      credits: course.credits, type: 'MANDATORY', specializationCodes: [], prerequisiteCourseCodes: []});
    seen.add(code);
    if (groupCode) next = assignTreeCourse(next, code, groupCode);
  }
  return deriveMandatory(next, catalog);
}
export function editCourse(tree, code, patch) {
  let next = structuredClone(tree);
  const row = treeCourses(tree).find(c => c.courseCode === code);
  if (!row) throw new Error('Không tìm thấy học phần trong cây.');
  const {semesterNumber = row.semesterNumber, ...fields} = patch;
  next = addSemester(next, semesterNumber);
  for (const semester of next.semesters) semester.courses = semester.courses.filter(c => c.courseCode !== code);
  const {semesterNumber: ignored, ...course} = row;
  next.semesters.find(s => s.semesterNumber === semesterNumber).courses.push({...course, ...fields});
  return next;
}
export function prerequisiteReason(tree, courseCode, candidate) {
  if (courseCode === candidate) return 'Không thể tự làm tiên quyết';
  const rows = treeCourses(tree), course = rows.find(c => c.courseCode === courseCode), prerequisite = rows.find(c => c.courseCode === candidate);
  if (!prerequisite) return 'Chưa nằm trong cây chuyên ngành';
  const visited = new Set();
  function reaches(code) {
    if (code === courseCode) return true;
    if (visited.has(code)) return false;
    visited.add(code);
    return (rows.find(c => c.courseCode === code)?.prerequisiteCourseCodes ?? []).some(reaches);
  }
  if (reaches(candidate)) return 'Sẽ tạo vòng lặp tiên quyết';
  if (!course || prerequisite.semesterNumber >= course.semesterNumber) return 'Tiên quyết phải ở học kỳ trước';
  return '';
}
export function builderWarnings(tree) {
  return [...treeWarnings(tree), ...treeCourses(tree).flatMap(c => (c.prerequisiteCourseCodes ?? []).flatMap(p => {
    const reason = prerequisiteReason(tree, c.courseCode, p);
    return reason ? [`Học kỳ ${c.semesterNumber} · ${c.courseCode} · ${p}: ${reason}.`] : [];
  }))];
}
export function validationLocation(message, trees, specs) {
  const text = String(message), tagged = specs.find(spec => text.includes('[' + spec.code + ']'));
  return specs.filter(spec => !tagged || spec.code === tagged.code).flatMap(spec => treeCourses(trees[spec.code]).filter(c =>
    c.courseCode && new RegExp('(^|[^A-Za-z0-9_-])' + c.courseCode.replace(/[.*+?^${}()|[\]\\]/g,'\\$&') + '($|[^A-Za-z0-9_-])').test(text))
    .map(c => `${spec.name} · Học kỳ ${c.semesterNumber} · ${c.courseCode}`)).join('; ');
}
export function groupCredits(tree, group, catalog) {
  const credits = new Map(catalog.map(c => [c.courseCode, c.credits]));
  return [...new Set(group.eligibleCourseCodes ?? [])].reduce((sum, code) => sum + (credits.get(code) ?? 0), 0);
}
