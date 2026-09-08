export function hasPermission(permissions: string[], node: string): boolean {
    return permissions.some((p) => {
        if (p === "*") return true;
        if (p.endsWith(".*")) return node.startsWith(p.slice(0, -1));
        return p === node;
    });
}

export function serverPermissions(
    globalPermissions: string[],
    scopedPermissions: Record<string, string[]>,
    serverId: string | undefined
): string[] {
    if (!serverId) return globalPermissions;
    const scoped = scopedPermissions[serverId] ?? [];
    return Array.from(new Set([...globalPermissions, ...scoped]));
}
