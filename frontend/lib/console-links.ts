const TOKEN_RE = /<[^>]*>|https?:\/\/[^\s<>"'`]+/gi;
const TRAILING_PUNCTUATION_RE = /[.,;:!?)\]}]+$/;

/**
 * Make URLs in already ANSI-converted console HTML clickable.
 *
 * The input is the output of `Anser.ansiToHtml`, so text is already HTML-escaped
 * (`&` is `&amp;`, which is valid inside an `href`) and the only markup present is
 * `<span>` tags. Scanning with a tag-or-URL alternation keeps attributes untouched
 * and only rewrites plain text, and URLs are limited to http(s) so log output can
 * never inject a `javascript:` target.
 */
export function linkifyConsoleHtml(html: string): string {
    if (!html) return html;
    return html.replace(TOKEN_RE, (token) => {
        if (token.startsWith("<")) return token;

        const url = token.replace(TRAILING_PUNCTUATION_RE, "");
        if (!url) return token;

        const trailing = token.slice(url.length);
        return `<a href="${url}" target="_blank" rel="noopener noreferrer" class="text-accent underline underline-offset-2 hover:text-accent-bright">${url}</a>${trailing}`;
    });
}
