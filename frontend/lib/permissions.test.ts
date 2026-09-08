import {describe, it, expect} from 'vitest'
import {hasPermission, serverPermissions} from './permissions'

describe('hasPermission', () => {
    it('grants everything with wildcard *', () => {
        expect(hasPermission(['*'], 'server.start')).toBe(true)
        expect(hasPermission(['*'], 'system.settings')).toBe(true)
    })

    it('grants matching namespace with .* wildcard', () => {
        expect(hasPermission(['server.*'], 'server.start')).toBe(true)
        expect(hasPermission(['server.*'], 'server.stop')).toBe(true)
        expect(hasPermission(['server.*'], 'server.console')).toBe(true)
    })

    it('does not grant different namespace with .* wildcard', () => {
        expect(hasPermission(['server.*'], 'system.settings')).toBe(false)
        expect(hasPermission(['system.*'], 'server.start')).toBe(false)
    })

    it('grants exact match', () => {
        expect(hasPermission(['server.start'], 'server.start')).toBe(true)
    })

    it('rejects non-matching exact permission', () => {
        expect(hasPermission(['server.start'], 'server.stop')).toBe(false)
    })

    it('returns false for empty permissions', () => {
        expect(hasPermission([], 'server.start')).toBe(false)
    })

    it('grants when any permission in list matches', () => {
        expect(hasPermission(['server.view', 'server.start'], 'server.start')).toBe(true)
    })

    it('does not treat partial prefix as wildcard', () => {
        expect(hasPermission(['server'], 'server.start')).toBe(false)
    })
})

describe('serverPermissions', () => {
    it('unions global and server-scoped permissions without duplicates', () => {
        const perms = serverPermissions(
            ['server.view'],
            {'s1': ['server.restart', 'server.view']},
            's1',
        )
        expect(perms.sort()).toEqual(['server.restart', 'server.view'])
    })

    it('falls back to global only when no server id is given', () => {
        expect(serverPermissions(['server.view'], {'s1': ['server.restart']}, undefined))
            .toEqual(['server.view'])
    })

    it('returns global only when the server has no scoped permissions', () => {
        expect(serverPermissions(['server.view'], {}, 's1')).toEqual(['server.view'])
    })

    it('scoped permissions alone surface when no global permissions exist', () => {
        expect(serverPermissions([], {'s1': ['server.restart']}, 's1'))
            .toEqual(['server.restart'])
    })
})
