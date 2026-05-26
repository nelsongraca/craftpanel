export function hasPermission(permissions: string[], node: string): boolean {
  return permissions.some((p) => {
    if (p === "*") return true;
    if (p.endsWith(".*")) return node.startsWith(p.slice(0, -1));
    return p === node;
  });
}
