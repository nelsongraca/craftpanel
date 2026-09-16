import {describe, it, expect} from 'vitest'
import {
    isCustomType,
    isModLoaderType,
    isPicolimboType,
    isProxyType,
    isVelocityType,
    modrinthKind,
    PROXY_TYPES,
} from './server-types'

describe('server-types', () => {
    it('classifies every proxy type, case- and whitespace-insensitively', () => {
        for (const type of PROXY_TYPES) expect(isProxyType(type)).toBe(true)
        expect(isProxyType('velocity')).toBe(true)
        expect(isProxyType('  BungeeCord ')).toBe(true)
        expect(isProxyType('PAPER')).toBe(false)
    })

    it('classifies custom, picolimbo and velocity', () => {
        expect(isCustomType('CUSTOM')).toBe(true)
        expect(isCustomType('custom')).toBe(true)
        expect(isCustomType('PAPER')).toBe(false)

        expect(isPicolimboType('PICOLIMBO')).toBe(true)
        expect(isPicolimboType('CUSTOM')).toBe(false)

        expect(isVelocityType('VELOCITY')).toBe(true)
        expect(isVelocityType('BUNGEECORD')).toBe(false)
    })

    it('classifies mod loaders and derives the Modrinth project kind', () => {
        expect(isModLoaderType('FABRIC')).toBe(true)
        expect(isModLoaderType('quilt')).toBe(true)
        expect(isModLoaderType('PAPER')).toBe(false)

        expect(modrinthKind('FORGE')).toBe('mod')
        expect(modrinthKind('PAPER')).toBe('plugin')
        expect(modrinthKind('VELOCITY')).toBe('plugin')
    })
})
