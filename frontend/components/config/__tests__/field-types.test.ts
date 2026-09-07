import {describe, it, expect} from 'vitest'
import type {FieldDef, Section, EditableBackend} from '../field-types'

describe('field-types', () => {
    it('FieldDef type accepts all valid shapes', () => {
        const textField: FieldDef = {key: 'MOTD', label: 'MOTD', type: 'text', serverPropertiesMapped: true}
        expect(textField.type).toBe('text')

        const toggleField: FieldDef = {key: 'PVP', label: 'PvP', type: 'toggle', serverPropertiesMapped: false}
        expect(toggleField.type).toBe('toggle')

        const selectField: FieldDef = {
            key: 'DIFFICULTY',
            label: 'Difficulty',
            type: 'select',
            options: ['easy', 'normal'],
            serverPropertiesMapped: true,
        }
        expect(selectField.options).toEqual(['easy', 'normal'])
    })

    it('FieldDef supports showWhen with value', () => {
        const field: FieldDef = {
            key: 'GENERATOR_SETTINGS',
            label: 'Gen',
            type: 'textarea',
            serverPropertiesMapped: true,
            showWhen: {key: 'LEVEL_TYPE', value: 'FLAT'},
        }
        expect(field.showWhen).toEqual({key: 'LEVEL_TYPE', value: 'FLAT'})
    })

    it('FieldDef supports showWhen with nonEmpty', () => {
        const field: FieldDef = {
            key: 'RESOURCE_PACK_SHA1',
            label: 'SHA1',
            type: 'text',
            serverPropertiesMapped: true,
            showWhen: {key: 'RESOURCE_PACK', nonEmpty: true},
        }
        expect(field.showWhen).toEqual({key: 'RESOURCE_PACK', nonEmpty: true})
    })

    it('Section type accepts optional collapsible and defaultOpen', () => {
        const section: Section = {
            title: 'Test',
            fields: [],
            collapsible: true,
            defaultOpen: false,
        }
        expect(section.collapsible).toBe(true)
        expect(section.defaultOpen).toBe(false)
    })

    it('EditableBackend type accepts all fields', () => {
        const backend: EditableBackend = {
            backendServerId: 'abc',
            backendName: 'survival',
            order: 1,
            displayName: 'Survival',
            serverType: 'PAPER',
            status: 'RUNNING',
        }
        expect(backend.backendName).toBe('survival')
    })
})