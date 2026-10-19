import { Route, Routes } from "react-router";

// Placeholder route table (Phase 1). Real pages are wired in as each user story lands:
// CatalogPage/CourseDetailPage (Phase 4), Login/Register (Phase 3), Learn pages (Phase 6),
// Verify/Account (Phases 7-8), NotFound (Phase 4).
function HomePlaceholder() {
  return <p>LearnHub — scaffold running.</p>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePlaceholder />} />
    </Routes>
  );
}
