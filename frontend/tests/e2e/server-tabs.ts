import type {Page} from "@playwright/test";
import {expect} from "./fixture";

/** aria-label on the phone tab picker's trigger (see the server detail page). */
export const TAB_PICKER = "Server section";

/**
 * Switch to a server-detail tab at any viewport. Below lg the tab strip is display:none — so it is
 * absent from the accessibility tree, not merely invisible — and the sections are chosen from a
 * Select picker instead. Decide by what is actually reachable rather than hardcoding a breakpoint.
 */
export async function selectServerTab(page: Page, label: string) {
    const strip = page.getByRole("tab", {name: label, exact: true});
    if (await strip.isVisible()) {
        await strip.click();
        return;
    }
    const picker = page.getByRole("combobox", {name: TAB_PICKER});
    await picker.click();
    await page.getByRole("option", {name: label, exact: true}).click();
    // base-ui closes the popup on select — confirm the trigger now shows the target.
    await expect(picker).toContainText(label);
}
