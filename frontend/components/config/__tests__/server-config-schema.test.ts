import {describe, it, expect} from 'vitest'
import {SECTIONS, SCHEMA_KEYS} from '../server-config-schema'

describe('server-config-schema', () => {
    it('exports sections with expected structure', () => {
        expect(Array.isArray(SECTIONS)).toBe(true)
        expect(SECTIONS.length).toBeGreaterThan(0)
    })

    it('every section has a title and fields', () => {
        for (const section of SECTIONS) {
            expect(section.title).toBeTruthy()
            expect(Array.isArray(section.fields)).toBe(true)
            expect(section.fields.length).toBeGreaterThan(0)
        }
    })

    it('every field has key, label, type, serverPropertiesMapped', () => {
        for (const section of SECTIONS) {
            for (const field of section.fields) {
                expect(field.key).toBeTruthy()
                expect(field.label).toBeTruthy()
                expect(['text', 'number', 'toggle', 'select', 'textarea', 'tag-input']).toContain(field.type)
                expect(typeof field.serverPropertiesMapped).toBe('boolean')
            }
        }
    })

    it('SCHEMA_KEYS contains all field keys', () => {
        const allKeys = SECTIONS.flatMap((s) => s.fields.map((f) => f.key))
        for (const key of allKeys) {
            expect(SCHEMA_KEYS.has(key)).toBe(true)
        }
        expect(SCHEMA_KEYS.size).toBe(allKeys.length)
    })

    it('collapsible sections have collapsible flag', () => {
        const collapsibleSections = SECTIONS.filter((s) => s.collapsible)
        expect(collapsibleSections.length).toBe(SECTIONS.length)
    })
})