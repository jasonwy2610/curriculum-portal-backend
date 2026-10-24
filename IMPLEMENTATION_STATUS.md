# HUFLIT Curriculum Portal — Implementation Patch

## Backend updates
- Notification read state is returned as a per-student `read` boolean; the API supports both mark-read and mark-unread actions.
- Active replacement rules send in-app notifications to active student accounts matching the configured major and cohorts when a rule is created or updated.
- Replacement-rule validation enforces 1-1, 1-N and N-1 cardinality after trimming and de-duplicating course codes.
- Changes to an existing curriculum and its knowledge groups, specializations, or semester/course structure notify students assigned to that curriculum.
- Student curriculum response includes knowledge-group progress and prerequisite readiness details for each course.
- Added unit tests for HUFLIT's score conversion bands and invalid score values.

## Frontend updates
- Student navigation includes a curriculum catalog/search view with curriculum detail.
- Student curriculum view shows group progress and unmet prerequisite course codes.
- Student notification UI uses the API's per-user `read` flag and can toggle read/unread.
- Admin navigation includes management screens for majors, courses, curriculums, and change history.
- Curriculum management supports create/edit/archive/clone, viewing curriculum structure, and adding/editing/removing knowledge groups, specializations, and courses in the curriculum.
- Admin academic-record screen supports looking up a student's records by MongoDB user ID and recording additional attempts.

## Verification
- Java source files and tests passed a Java compiler parse/syntax check.
- React JSX passed TypeScript's JSX transpilation syntax check.
- A full Maven test run and Vite production build could not be completed in this environment because the Maven wrapper and npm dependencies could not be downloaded before the network timeout. Run the commands below in the project environment after dependencies are available.

## Local verification commands
Backend (Windows PowerShell, project root):
```powershell
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

Frontend:
```powershell
cd frontend
npm ci
npm run build
npm run dev
```

The project uses the existing configuration files from the supplied archive; this patch does not intentionally change database credentials or JWT settings.
