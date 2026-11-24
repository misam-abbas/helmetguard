import { describe, expect, it } from "vitest";
import { parsePeriod, rangeForPeriod, startOfTodayPakistan } from "../src/lib/filters";

describe("startOfTodayPakistan", () => {
  it("is midnight in Pakistan (19:00 UTC the day before)", () => {
    expect(startOfTodayPakistan(new Date("2026-11-25T01:30:00Z")).toISOString()).toBe("2026-11-24T19:00:00.000Z");
  });

  it("moves to the next day at 19:00 UTC, which is midnight in Pakistan", () => {
    expect(startOfTodayPakistan(new Date("2026-11-24T18:59:59Z")).toISOString()).toBe("2026-11-23T19:00:00.000Z");
    expect(startOfTodayPakistan(new Date("2026-11-24T19:00:00Z")).toISOString()).toBe("2026-11-24T19:00:00.000Z");
  });
});

describe("rangeForPeriod", () => {
  const now = new Date("2026-11-25T01:30:00Z");

  it("counts today from midnight Pakistan time, and the last 7 or 30 days back from now", () => {
    expect(rangeForPeriod("today", now).from).toBe("2026-11-24T19:00:00.000Z");
    expect(rangeForPeriod("7", now).from).toBe("2026-11-18T01:30:00.000Z");
    expect(rangeForPeriod("30", now).from).toBe("2026-10-26T01:30:00.000Z");
  });

  it("does not cut off records stamped a little after now", () => {
    expect(rangeForPeriod("today", now).to).toBe("2026-11-26T01:30:00.000Z");
  });
});

describe("parsePeriod", () => {
  it("defaults to today, like the app's home screen", () => {
    expect(parsePeriod({})).toBe("today");
    expect(parsePeriod({ period: "7" })).toBe("7");
    expect(parsePeriod({ period: "365" })).toBe("today");
  });
});
