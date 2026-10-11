import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAutoLock } from "../../../src/components/security/useAutoLock";

const MINUTE = 60_000;

describe("auto-lock", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("locks once nobody has touched the window for the time set", () => {
    const lock = vi.fn();
    renderHook(() => useAutoLock(5, true, lock, vi.fn()));

    vi.advanceTimersByTime(5 * MINUTE - 10_000);
    expect(lock).not.toHaveBeenCalled();

    vi.advanceTimersByTime(10_000);
    expect(lock).toHaveBeenCalled();
  });

  it("starts the time again whenever someone uses the window", () => {
    const lock = vi.fn();
    renderHook(() => useAutoLock(5, true, lock, vi.fn()));

    vi.advanceTimersByTime(4 * MINUTE);
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "a" }));
    vi.advanceTimersByTime(4 * MINUTE);
    expect(lock).not.toHaveBeenCalled();

    vi.advanceTimersByTime(MINUTE);
    expect(lock).toHaveBeenCalled();
  });

  it("tells the backend someone is still here while they work, and not while they are away", () => {
    const keepAlive = vi.fn();
    renderHook(() => useAutoLock(15, true, vi.fn(), keepAlive));

    // Away: nothing to report.
    vi.advanceTimersByTime(2 * MINUTE);
    expect(keepAlive).not.toHaveBeenCalled();

    // Typing in a form sends no requests of its own, so the window speaks up for it.
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "a" }));
    vi.advanceTimersByTime(5_000);
    expect(keepAlive).toHaveBeenCalledTimes(1);
  });

  it("does nothing when there is no PIN to lock with", () => {
    const lock = vi.fn();
    renderHook(() => useAutoLock(1, false, lock, vi.fn()));

    vi.advanceTimersByTime(10 * MINUTE);

    expect(lock).not.toHaveBeenCalled();
  });
});
