type MojangVersion = { id: string; type: string };

/** Fetch Minecraft release version ids from Mojang's version manifest. Returns [] on failure. */
export async function fetchReleaseVersions(): Promise<string[]> {
    try {
        const res = await fetch("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");
        const json = await res.json() as { versions: MojangVersion[] };
        return json.versions.filter((v) => v.type === "release").map((v) => v.id);
    } catch {
        return [];
    }
}
