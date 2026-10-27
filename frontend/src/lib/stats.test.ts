import { describe, expect, it } from "vitest";
import { completionRate, mergeCourses, sortRows, type CourseRow } from "./stats";

const mine = [
  { id: "a", title: "Alpha", status: "PUBLISHED" },
  { id: "b", title: "Bravo draft", status: "DRAFT" },
  { id: "c", title: "Charlie archived", status: "ARCHIVED" },
];

const stats = [
  { courseId: "a", totals: { enrollments: 4, revenueCents: 7996, completions: 1, certificates: 1 } },
  { courseId: "c", totals: { enrollments: 2, revenueCents: 0, completions: 2, certificates: 0 } },
];

describe("completionRate", () => {
  it("is completions over enrollments", () => {
    expect(completionRate(1, 4)).toBe(0.25);
  });

  it("is 0 when there are no enrollments", () => {
    expect(completionRate(0, 0)).toBe(0);
    expect(completionRate(3, 0)).toBe(0);
  });

  it("never exceeds 100%", () => {
    expect(completionRate(9, 4)).toBe(1);
  });
});

describe("mergeCourses", () => {
  it("keeps every owned course, including drafts and archived, with zeros when there is no activity", () => {
    const rows = mergeCourses(mine, stats);

    expect(rows.map((r) => r.courseId)).toEqual(["a", "b", "c"]);
    const draft = rows[1]!;
    expect(draft.title).toBe("Bravo draft");
    expect(draft.status).toBe("DRAFT");
    expect([draft.enrollments, draft.revenueCents, draft.completions, draft.certificates]).toEqual([0, 0, 0, 0]);
    expect(draft.completionRate).toBe(0);
  });

  it("carries the figures and the completion rate of a course with activity", () => {
    const alpha = mergeCourses(mine, stats)[0]!;

    expect(alpha.enrollments).toBe(4);
    expect(alpha.revenueCents).toBe(7996);
    expect(alpha.completionRate).toBe(0.25);
  });

  it("ignores activity for courses the caller does not own", () => {
    const rows = mergeCourses(mine, [
      ...stats,
      { courseId: "zzz", totals: { enrollments: 99, revenueCents: 99, completions: 99, certificates: 99 } },
    ]);

    expect(rows).toHaveLength(3);
  });
});

describe("sortRows", () => {
  const rows: CourseRow[] = mergeCourses(mine, stats);

  it("sorts by enrollments descending and ascending", () => {
    expect(sortRows(rows, "enrollments", "desc").map((r) => r.courseId)).toEqual(["a", "c", "b"]);
    expect(sortRows(rows, "enrollments", "asc").map((r) => r.courseId)).toEqual(["b", "c", "a"]);
  });

  it("sorts by revenue", () => {
    expect(sortRows(rows, "revenueCents", "desc")[0]!.courseId).toBe("a");
  });

  it("sorts by completion rate (c is 100%, a is 25%, b is 0%)", () => {
    expect(sortRows(rows, "completionRate", "desc").map((r) => r.courseId)).toEqual(["c", "a", "b"]);
  });

  it("breaks ties by title so the order is stable, and does not change the input", () => {
    const tied = mergeCourses(mine, []);

    expect(sortRows(tied, "enrollments", "desc").map((r) => r.title)).toEqual([
      "Alpha",
      "Bravo draft",
      "Charlie archived",
    ]);
    expect(tied.map((r) => r.courseId)).toEqual(["a", "b", "c"]);
  });
});
