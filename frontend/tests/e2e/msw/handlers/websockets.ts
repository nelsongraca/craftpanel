import {ws} from "msw";
import {dashboardSnapshot} from "../fixtures/data";

export const dashboardWs = ws.link("ws://localhost:3000/api/ws");
export const consoleWs = ws.link("ws://localhost:3000/api/ws/console/*");
export const migrationWs = ws.link(
    "ws://localhost:3000/api/migrations/*/events"
);

export const dashboardWsHandlers = [
    dashboardWs.addEventListener("connection", ({client}) => {
        client.send(JSON.stringify(dashboardSnapshot));
    }),
];

export const consoleWsHandlers = [
    consoleWs.addEventListener("connection", ({client}) => {
        client.send(JSON.stringify({type: "console.ready"}));
    }),
];

export const migrationWsHandlers = [
    migrationWs.addEventListener("connection", ({client}) => {
        client.send(
            JSON.stringify({type: "status", payload: {status: "IN_PROGRESS"}})
        );
    }),
];
