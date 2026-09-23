import {describe, it, expect} from "vitest";
import {allocatable, fmtBytes, fmtMb, fillColor, fillColorBg, timeAgo} from "./format";

describe("fmtBytes", () => {
    it("formats bytes below 1 KB", () => {
        expect(fmtBytes(0)).toBe("0 B");
        expect(fmtBytes(500)).toBe("500 B");
        expect(fmtBytes(1023)).toBe("1023 B");
    });

    it("formats KB boundary", () => {
        expect(fmtBytes(1024)).toBe("1.0 KB");
        expect(fmtBytes(1536)).toBe("1.5 KB");
    });

    it("formats MB boundary", () => {
        expect(fmtBytes(1_048_576)).toBe("1.0 MB");
        expect(fmtBytes(52_428_800)).toBe("50.0 MB");
    });

    it("formats GB boundary", () => {
        expect(fmtBytes(1_073_741_824)).toBe("1.0 GB");
        expect(fmtBytes(2_147_483_648)).toBe("2.0 GB");
    });
});

describe("timeAgo", () => {
    const now = new Date("2024-01-01T12:00:00.000Z").getTime();

    beforeEach(() => {
        vi.spyOn(Date, "now").mockReturnValue(now);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it("shows seconds for < 60s", () => {
        const iso = new Date(now - 30_000).toISOString();
        expect(timeAgo(iso)).toBe("30 seconds ago");
    });

    it("shows minutes for < 1h", () => {
        const iso = new Date(now - 5 * 60_000).toISOString();
        expect(timeAgo(iso)).toBe("5 minutes ago");
    });

    it("shows hours for < 24h", () => {
        const iso = new Date(now - 3 * 3_600_000).toISOString();
        expect(timeAgo(iso)).toBe("3 hours ago");
    });

    it("shows days for >= 24h", () => {
        const iso = new Date(now - 2 * 86_400_000).toISOString();
        expect(timeAgo(iso)).toBe("2 days ago");
    });
});

describe("fmtMb", () => {
    it("returns MB below 1024", () => {
        expect(fmtMb(512)).toBe("512 MB");
        expect(fmtMb(1023)).toBe("1023 MB");
    });

    it("returns GB at 1024+", () => {
        expect(fmtMb(1024)).toBe("1.0 GB");
        expect(fmtMb(2048)).toBe("2.0 GB");
    });
});

describe("allocatable", () => {
    it("subtracts the reserve from the total", () => {
        expect(allocatable(32768, 1024)).toBe(31744);
    });

    it("treats a missing reserve as zero", () => {
        expect(allocatable(32768)).toBe(32768);
        expect(allocatable(32768, null)).toBe(32768);
    });

    it("never goes negative", () => {
        expect(allocatable(1024, 4096)).toBe(0);
    });
});

describe("fillColor", () => {
    it("error at 86+", () => {
        expect(fillColor(86)).toBe("var(--error)");
    });
    it("warning at 66-85", () => {
        expect(fillColor(66)).toBe("var(--warning)");
    });
    it("healthy below 66", () => {
        expect(fillColor(65)).toBe("var(--healthy)");
    });
});

describe("fillColorBg", () => {
    it("bg-error at 86+", () => {
        expect(fillColorBg(100)).toBe("bg-error");
    });
    it("bg-warning at 66-85", () => {
        expect(fillColorBg(70)).toBe("bg-warning");
    });
    it("bg-healthy below 66", () => {
        expect(fillColorBg(0)).toBe("bg-healthy");
    });
});
