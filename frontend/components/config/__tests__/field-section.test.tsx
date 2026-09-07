import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import {FieldSection} from '../field-section'
import type {Section} from '../field-types'

describe('FieldSection', () => {
    const form: Record<string, string> = {}

    it('renders section title', () => {
        const section: Section = {title: 'Gameplay', fields: [{key: 'PVP', label: 'PvP', type: 'toggle', serverPropertiesMapped: true}]}
        render(<FieldSection section={section} form={form} setField={vi.fn()}/>)
        expect(screen.getByText('Gameplay')).toBeInTheDocument()
    })

    it('renders fields', () => {
        const section: Section = {
            title: 'Gameplay',
            fields: [
                {key: 'PVP', label: 'PvP', type: 'toggle', serverPropertiesMapped: true},
                {key: 'MODE', label: 'Mode', type: 'select', options: ['survival'], serverPropertiesMapped: true},
            ],
        }
        render(<FieldSection section={section} form={form} setField={vi.fn()}/>)
        expect(screen.getByText('PvP')).toBeInTheDocument()
        expect(screen.getByText('Mode')).toBeInTheDocument()
    })

    it('hides fields whose showWhen condition is not met', () => {
        const section: Section = {
            title: 'World',
            fields: [
                {key: 'GENERATOR_SETTINGS', label: 'Gen', type: 'text', serverPropertiesMapped: true, showWhen: {key: 'LEVEL_TYPE', value: 'FLAT'}},
            ],
        }
        render(<FieldSection section={section} form={{LEVEL_TYPE: 'DEFAULT'}} setField={vi.fn()}/>)
        expect(screen.queryByText('Gen')).not.toBeInTheDocument()
    })

    it('shows fields when showWhen condition matches', () => {
        const section: Section = {
            title: 'World',
            fields: [
                {key: 'GENERATOR_SETTINGS', label: 'Gen', type: 'text', serverPropertiesMapped: true, showWhen: {key: 'LEVEL_TYPE', value: 'FLAT'}},
            ],
        }
        render(<FieldSection section={section} form={{LEVEL_TYPE: 'FLAT'}} setField={vi.fn()}/>)
        expect(screen.getByText('Gen')).toBeInTheDocument()
    })

    it('hides fields when nonEmpty condition fails', () => {
        const section: Section = {
            title: 'RP',
            fields: [
                {key: 'RESOURCE_PACK_SHA1', label: 'SHA1', type: 'text', serverPropertiesMapped: true, showWhen: {key: 'RESOURCE_PACK', nonEmpty: true}},
            ],
        }
        render(<FieldSection section={section} form={{RESOURCE_PACK: ''}} setField={vi.fn()}/>)
        expect(screen.queryByText('SHA1')).not.toBeInTheDocument()
    })

    it('shows fields when nonEmpty condition passes', () => {
        const section: Section = {
            title: 'RP',
            fields: [
                {key: 'RESOURCE_PACK_SHA1', label: 'SHA1', type: 'text', serverPropertiesMapped: true, showWhen: {key: 'RESOURCE_PACK', nonEmpty: true}},
            ],
        }
        render(<FieldSection section={section} form={{RESOURCE_PACK: 'https://example.com/pack.zip'}} setField={vi.fn()}/>)
        expect(screen.getByText('SHA1')).toBeInTheDocument()
    })

    it('returns null when all fields are hidden', () => {
        const section: Section = {
            title: 'Hidden',
            fields: [
                {key: 'X', label: 'X', type: 'text', serverPropertiesMapped: true, showWhen: {key: 'Y', value: 'Z'}},
            ],
        }
        const {container} = render(<FieldSection section={section} form={{Y: 'A'}} setField={vi.fn()}/>)
        expect(container.innerHTML).toBe('')
    })

    it('renders collapsible sections with trigger', () => {
        const section: Section = {
            title: 'Performance',
            collapsible: true,
            fields: [{key: 'VIEW_DISTANCE', label: 'View Distance', type: 'number', serverPropertiesMapped: true}],
        }
        render(<FieldSection section={section} form={form} setField={vi.fn()}/>)
        expect(screen.getByText('Performance')).toBeInTheDocument()
    })
})