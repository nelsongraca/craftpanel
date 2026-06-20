import {authHandlers} from "./auth";
import {serverHandlers} from "./servers";
import {nodeHandlers} from "./nodes";

// WS handlers are NOT included by default — they cause @msw/playwright to
// install routeWebSocket(MATCH_ALL) which intercepts Turbopack's HMR WebSocket
// and breaks React hydration. Import and add them per-test via network.use().
export const handlers = [
    ...authHandlers,
    ...serverHandlers,
    ...nodeHandlers,
];

export {dashboardWsHandlers, consoleWsHandlers, migrationWsHandlers} from "./websockets";
