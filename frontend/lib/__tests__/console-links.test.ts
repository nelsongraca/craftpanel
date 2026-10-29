import {describe, it, expect} from "vitest";
import {linkifyConsoleHtml} from "@/lib/console-links";

describe("linkifyConsoleHtml", () => {
    it("wraps plain http(s) URLs in an anchor", () => {
        expect(linkifyConsoleHtml("See https://example.com/docs for details")).toBe(
            'See <a href="https://example.com/docs" target="_blank" rel="noopener noreferrer" class="text-accent underline underline-offset-2 hover:text-accent-bright">https://example.com/docs</a> for details',
        );
    });

    it("keeps surrounding ANSI span markup untouched", () => {
        const html = '<span style="color:#00ff00">https://example.com</span>';
        expect(linkifyConsoleHtml(html)).toBe(
            '<span style="color:#00ff00"><a href="https://example.com" target="_blank" rel="noopener noreferrer" class="text-accent underline underline-offset-2 hover:text-accent-bright">https://example.com</a></span>',
        );
    });

    it("leaves trailing punctuation outside the link", () => {
        expect(linkifyConsoleHtml("Go to https://example.com/a?b=1.")).toContain(
            'class="text-accent underline underline-offset-2 hover:text-accent-bright">https://example.com/a?b=1</a>.',
        );
    });

    it("does not linkify non-http schemes", () => {
        const html = "javascript:alert(1) file:///etc/passwd ftp://example.com";
        expect(linkifyConsoleHtml(html)).toBe(html);
    });

    it("does not rewrite URLs inside existing tag attributes", () => {
        const html = '<span data-href="https://example.com">text</span>';
        expect(linkifyConsoleHtml(html)).toBe(html);
    });

    it("returns empty input unchanged", () => {
        expect(linkifyConsoleHtml("")).toBe("");
    });
});
