import {defineConfig} from 'vitest/config'
import react from '@vitejs/plugin-react'
import {resolve} from 'path'

export default defineConfig({
    plugins: [react()],
    test: {
        environment: 'jsdom',
        setupFiles: ['./vitest.setup.ts'],
        globals: true,
        exclude: ['**/node_modules/**', '**/tests/e2e/**'],
        reporters: ['default', ['junit', {outputFile: './build/reports/junit/vitest.xml'}]],
        coverage: {
            provider: 'v8',
            reporter: ['text', 'html', 'lcov'],
            reportsDirectory: './build/reports/coverage',
            include: ['app/**', 'lib/**', 'components/**'],
            exclude: ['lib/generated/**', '**/*.test.*', '**/__tests__/**'],
        },
    },
    resolve: {
        alias: { '@': resolve(__dirname, '.') },
    },
})
