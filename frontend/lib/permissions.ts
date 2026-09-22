export function hasPermission(permissions: string[], node: string): boolean {
    return permissions.some((p) => {
        if (p === "*") return true;
        if (p.endsWith(".*")) return node.startsWith(p.slice(0, -1));
        return p === node;
    });
}

/**
 * Effective permissions for a resource (`serverId` or `networkId`): the caller's globals plus any
 * permissions scoped to that resource, de-duplicated. With no resource id, the globals alone.
 */
export function scopedPermissions(
    globalPermissions: string[],
    scopedPermissions: Record<string, string[]>,
    resourceId: string | undefined
): string[] {
    if (!resourceId) return globalPermissions;
    const scoped = scopedPermissions[resourceId] ?? [];
    return Array.from(new Set([...globalPermissions, ...scoped]));
}
