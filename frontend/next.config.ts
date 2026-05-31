import type {NextConfig} from "next";

const nextConfig: NextConfig = {
    output: "standalone",
    async rewrites() {
        const masterUrl = process.env.MASTER_URL ?? "http://localhost:8080";
        return [
            {
                source: "/api/:path*",
                destination: `${masterUrl}/api/:path*`,
            },
        ];
    },
};

export default nextConfig;
