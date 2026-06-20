import {test as base} from "@playwright/test";
import {type AnyHandler} from "msw";
import {defineNetworkFixture, type NetworkFixture} from "@msw/playwright";
import {handlers} from "./msw/handlers";

interface Fixtures {
    extraHandlers: AnyHandler[];
    network: NetworkFixture;
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
});

export {expect} from "@playwright/test";
