import {screen} from "@testing-library/react";
import type {UserEvent} from "@testing-library/user-event";

/**
 * Selects an option in a shadcn/base-ui Select (role="combobox" trigger + role="option" items).
 * Note: the trigger's accessible `name` is empty (base-ui's combobox role doesn't derive a name
 * from its SelectValue subtree) — locate the trigger positionally or via `toHaveTextContent`,
 * not `getByRole('combobox', {name: ...})`.
 */
export async function selectComboboxOption(user: UserEvent, combobox: HTMLElement, optionName: string | RegExp) {
    await user.click(combobox);
    await user.click(await screen.findByRole("option", {name: optionName}));
}
