export const ruleStatuses = {
  DRAFT: 'Bản nháp',
  SCHEDULED: 'Chờ hiệu lực',
  ACTIVE: 'Đang áp dụng',
  INACTIVE: 'Ngừng áp dụng'
};

export function isPublished(rule) {
  return rule.lifecycleStatus === 'PUBLISHED' || (rule.lifecycleStatus == null && rule.status === 'ACTIVE');
}

export function ruleFamily(rule) {
  return rule.ruleFamilyId ?? rule.id;
}

export function ruleWindows(rules) {
  const families = new Map();
  for (const rule of rules) {
    if (!isPublished(rule) || !rule.effectiveDate || !rule.id) continue;
    const start = Math.max(Date.parse(rule.effectiveDate), Date.parse(rule.publishedAt || rule.effectiveDate));
    const ownEnd = rule.endsAt ? Date.parse(rule.endsAt) : Infinity;
    if (!Number.isFinite(start) || ownEnd <= start) continue;
    const family = ruleFamily(rule);
    if (!families.has(family)) families.set(family, []);
    families.get(family).push({rule, start, ownEnd});
  }

  const windows = new Map();
  for (const versions of families.values()) {
    versions.sort((a, b) => (b.rule.versionNumber ?? 1) - (a.rule.versionNumber ?? 1)
      || b.start - a.start || b.rule.id.localeCompare(a.rule.id));
    let nextStart = Infinity;
    for (const {rule, start, ownEnd} of versions) {
      if (start >= nextStart) continue;
      if (rule.status === 'ACTIVE') windows.set(rule.id, {start, end: Math.min(ownEnd, nextStart)});
      nextStart = start;
    }
  }
  return windows;
}

export function ruleStatus(rule, windows, at = Date.now()) {
  if (rule.lifecycleStatus === 'DRAFT') return 'DRAFT';
  const window = windows.get(rule.id);
  if (!window || at >= window.end) return 'INACTIVE';
  return at < window.start ? 'SCHEDULED' : 'ACTIVE';
}

const vietnamTime = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
});

export function vietnamDateTimeInput(value) {
  return Number.isFinite(new Date(value).getTime()) && value
    ? vietnamTime.format(new Date(value)).replace(' ', 'T') : '';
}

export function vietnamDateTimeIso(value) {
  if (!value) return null;
  const date = new Date(value + '+07:00');
  return Number.isFinite(date.getTime()) ? date.toISOString() : null;
}

export function replacementCourses(rules, student, curriculum, semester, completedAt) {
  const at = Date.parse(completedAt);
  if (!student || !curriculum || !semester || !Number.isFinite(at)) return new Set();
  const matchesSpecialization = codes => !codes?.length || codes.includes(student.chosenSpecializationCode);
  const programCodes = new Set((curriculum.semesters ?? []).flatMap(item => (item.courses ?? [])
    .filter(course => matchesSpecialization(course.specializationCodes)).map(course => course.courseCode)));
  for (const group of curriculum.requirementGroups ?? []) {
    if (!matchesSpecialization(group.specializationCodes)) continue;
    for (const code of group.eligibleCourseCodes ?? []) programCodes.add(code);
    for (const option of group.completionOptions ?? []) {
      for (const code of option.courseCodes ?? []) programCodes.add(code);
    }
  }
  const semesterCodes = new Set((semester.courses ?? []).map(course => course.courseCode));
  const windows = ruleWindows(rules);
  return new Set(rules.filter(rule => rule.publishedAt && rule.majorId === student.majorId
    && rule.applicableCohorts?.includes(student.cohort) && rule.oldCourseCodes?.length
    && rule.oldCourseCodes.every(code => programCodes.has(code))
    && rule.oldCourseCodes.some(code => semesterCodes.has(code))
    && ruleStatus(rule, windows, at) === 'ACTIVE').flatMap(rule => rule.newCourseCodes ?? []));
}
