import {http, HttpResponse} from "msw";
import {fakeNode, fakeNode2, fakeNetwork} from "../fixtures/data";

export const nodeHandlers = [
    http.get("/api/nodes", () => HttpResponse.json([fakeNode, fakeNode2])),

    http.get("/api/networks", () => HttpResponse.json([fakeNetwork])),
];
