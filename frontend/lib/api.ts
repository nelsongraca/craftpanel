export class ApiError extends Error {
    constructor(
        public readonly status: number | undefined,
        message: string,
    ) {
        super(message)
        this.name = 'ApiError'
    }
}

type SdkResult<T> = Promise<{ data?: T; error?: { message?: string }; response?: Response }>

export async function call<T>(fn: () => SdkResult<T>): Promise<T> {
    const { data, error, response } = await fn()
    if (error) throw new ApiError(response?.status, error.message ?? 'Request failed')
    return data as T
}

export async function tryCall<T>(
    fn: () => SdkResult<T>,
): Promise<{ ok: true; data: T } | { ok: false; error: string; status?: number }> {
    try {
        const { data, error, response } = await fn()
        if (error) return { ok: false, error: error.message ?? 'Request failed', status: response?.status }
        return { ok: true, data: data as T }
    } catch (e) {
        return { ok: false, error: e instanceof Error ? e.message : 'Request failed' }
    }
}
