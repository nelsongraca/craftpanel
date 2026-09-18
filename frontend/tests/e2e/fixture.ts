import {test as base} from "@playwright/test";
import {type AnyHandler} from "msw";
import {defineNetworkFixture, type NetworkFixture} from "@msw/playwright";
import {handlers} from "./msw/handlers";
import {resetConfig} from "./msw/handlers/config";
import {resetMods} from "./msw/handlers/mods";
import {resetBackups} from "./msw/handlers/backups";
import {startJSCoverage, stopJSCoverage} from "./coverage";

interface Fixtures {
    extraHandlers: AnyHandler[];
    network: NetworkFixture;
    _coverage: void;
}

export const test = base.extend<Fixtures>({
    extraHandlers: [[], {option: true}],

    network: [
        async ({context, extraHandlers}, use) => {
            const network = defineNetworkFixture({
                context,
                handlers: [...handlers, ...extraHandlers],
                onUnhandledRequest: "bypass",
            });
            await network.enable();
            await use(network);
            await network.disable();
        },
        {auto: true},
    ],

    _coverage: [
        async ({page}, use) => {
            await startJSCoverage(page);
            await use();
            await stopJSCoverage(page);
        },
        {auto: true},
    ],
});

// Reset any mutable handler state so tests cannot leak into one another.
test.beforeEach(() => {
    resetConfig();
    resetMods();
    resetBackups();
});

export {expect} from "@playwright/test";
