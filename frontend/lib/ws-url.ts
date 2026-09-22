import {authWsTicket} from "@/lib/generated/sdk.gen";
import {getAccessToken} from "@/lib/client";

/**
 * Build an absolute, ticket-authenticated WebSocket URL for [path] (e.g. `/api/ws/console/<id>`).
 * Returns null when the user has no access token or the ticket request fails — callers decide
 * whether that is an error worth surfacing.
 */
export async function ticketWsUrl(path: string): Promise<string | null> {
    if (!getAccessToken()) return null;
    const res = await authWsTicket();
    const ticket = res?.data?.ticket;
    if (!ticket) return null;
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${window.location.host}${path}?ticket=${ticket}`;
}
