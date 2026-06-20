import {http, HttpResponse} from "msw";
import {
    loginResponse,
    wsTicketResponse,
    fakeUser,
} from "../fixtures/data";

export const authHandlers = [
    http.post("/api/auth/login", () => HttpResponse.json(loginResponse)),

    // Default: always return a valid refresh so any page navigation stays authenticated.
    // Tests that need an unauthenticated state should override this with network.use().
    http.post("/api/auth/refresh", () => HttpResponse.json(loginResponse)),

    http.get("/api/auth/me", () => HttpResponse.json(fakeUser)),

    http.post("/api/auth/ws-ticket", () => HttpResponse.json(wsTicketResponse)),

    http.post("/api/auth/logout", () => new HttpResponse(null, {status: 204})),
];
