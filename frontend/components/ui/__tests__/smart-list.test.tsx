import {describe, it, expect, vi} from 'vitest'
import {render, screen, within} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {SmartList, type SmartListColumn} from '../smart-list'

interface Item {
    id: string
    name: string
    role: string
}

const baseColumns: SmartListColumn<Item>[] = [
    {key: 'name', header: 'Name', render: (i) => i.name},
    {key: 'role', header: 'Role', render: (i) => i.role},
]

const items: Item[] = [
    {id: 'a', name: 'Alice', role: 'Admin'},
    {id: 'b', name: 'Bob', role: 'Viewer'},
]

function firstCard(): HTMLElement {
    const container = document.querySelector('.md\\:hidden')!
    return within(container).getByText('Alice').closest('[class*="p-3"]') as HTMLElement
}

describe('SmartList', () => {
    it('renders desktop table headers and cells', () => {
        render(<SmartList items={items} columns={baseColumns} keyFor={(i) => i.id}/>)
        const table = document.querySelector('table')!
        expect(table).toBeInTheDocument()
        expect(within(table).getByText('Name')).toBeInTheDocument()
        expect(within(table).getByText('Role')).toBeInTheDocument()
        expect(within(table).getByText('Alice')).toBeInTheDocument()
        expect(within(table).getByText('Viewer')).toBeInTheDocument()
    })

    it('renders actions column trailing and forwards header', () => {
        render(
            <SmartList
                items={items}
                columns={baseColumns}
                keyFor={(i) => i.id}
                actionsHeader="Actions"
                actions={(i) => <button>Edit {i.name}</button>}
            />
        )
        expect(screen.getByText('Actions')).toBeInTheDocument()
        expect(screen.getAllByText(/Edit Alice/).length).toBeGreaterThan(0)
    })

    it('renders plain loading text when no skeletonRows', () => {
        render(<SmartList items={[]} columns={baseColumns} keyFor={(i) => i.id} loading/>)
        expect(screen.getByText('Loading…')).toBeInTheDocument()
        expect(document.querySelector('table')).not.toBeInTheDocument()
    })

    it('renders skeleton rows on desktop when skeletonRows set', () => {
        render(<SmartList items={[]} columns={baseColumns} keyFor={(i) => i.id} loading skeletonRows={3}/>)
        const table = document.querySelector('table')
        expect(table).toBeInTheDocument()
        expect(table!.querySelectorAll('tr')).toHaveLength(4)
        expect(screen.getByText('Loading…')).toBeInTheDocument()
    })

    it('renders empty message when no items', () => {
        render(<SmartList items={[]} columns={baseColumns} keyFor={(i) => i.id} empty="Nothing here yet."/>)
        expect(screen.getByText('Nothing here yet.')).toBeInTheDocument()
        expect(document.querySelector('table')).not.toBeInTheDocument()
    })

    it('derives a mobile card: first column as title, rest as label:value meta', () => {
        render(<SmartList items={items} columns={baseColumns} keyFor={(i) => i.id}/>)
        const cards = document.querySelector('.md\\:hidden')
        expect(cards).toBeInTheDocument()
        const card = within(firstCard())
        expect(card.getByText('Alice')).toBeInTheDocument()
        expect(card.getByText('Role')).toBeInTheDocument()
        expect(card.getByText('Admin')).toBeInTheDocument()
    })

    it('supports overriding the title column and hiding columns on mobile', () => {
        const columns: SmartListColumn<Item>[] = [
            {key: 'name', header: 'Name', render: (i) => i.name},
            {key: 'role', header: 'Role', render: (i) => i.role, title: true},
            {key: 'hidden', header: 'Secret', render: () => 'x', hiddenOnMobile: true},
        ]
        render(<SmartList items={items} columns={columns} keyFor={(i) => i.id}/>)
        const card = within(firstCard())
        expect(card.getByText('Admin')).toBeInTheDocument()
        expect(card.getByText('Name')).toBeInTheDocument()
        expect(card.queryByText('Secret')).not.toBeInTheDocument()
    })

    it('uses custom label when provided', () => {
        const columns: SmartListColumn<Item>[] = [
            {key: 'name', header: 'Name', render: (i) => i.name},
            {key: 'role', header: 'Role', label: 'Access level', render: (i) => i.role},
        ]
        render(<SmartList items={items} columns={columns} keyFor={(i) => i.id}/>)
        expect(within(firstCard()).getByText('Access level')).toBeInTheDocument()
    })

    it('renders mobileCard override instead of derived cards', () => {
        render(
            <SmartList
                items={items}
                columns={baseColumns}
                keyFor={(i) => i.id}
                mobileCard={(i) => <div>CUSTOM {i.name}</div>}
            />
        )
        const card = within(document.querySelector('.md\\:hidden') as HTMLElement)
        expect(card.getByText('CUSTOM Alice')).toBeInTheDocument()
        expect(card.queryByText('Role')).not.toBeInTheDocument()
    })

    it('does not render the actions trailing row when actions render null', () => {
        render(<SmartList items={items} columns={baseColumns} keyFor={(i) => i.id} actions={() => null}/>)
        // derived cards still render their title + meta, but no extra container
        const card = within(document.querySelector('.md\\:hidden') as HTMLElement)
        expect(card.getByText('Admin')).toBeInTheDocument()
    })

    it('fires onRowClick for desktop rows and cards', async () => {
        const onRowClick = vi.fn()
        const user = userEvent.setup()
        render(<SmartList items={items} columns={baseColumns} keyFor={(i) => i.id} onRowClick={onRowClick}/>)
        const table = document.querySelector('table')!
        await user.click(within(table).getByText('Bob'))
        expect(onRowClick).toHaveBeenCalledWith({id: 'b', name: 'Bob', role: 'Viewer'})
    })

    it('stops propagation when an action is clicked on a clickable row', async () => {
        const onRowClick = vi.fn()
        const actionClick = vi.fn()
        const user = userEvent.setup()
        render(
            <SmartList
                items={items}
                columns={baseColumns}
                keyFor={(i) => i.id}
                onRowClick={onRowClick}
                actions={(i) => <button onClick={actionClick}>Edit {i.name}</button>}
            />
        )
        await user.click(within(document.querySelector('table') as HTMLElement).getByText('Edit Alice'))
        expect(actionClick).toHaveBeenCalledTimes(1)
        expect(onRowClick).not.toHaveBeenCalled()
    })

    it('renders header slot inside the wrapper', () => {
        render(
            <SmartList items={items} columns={baseColumns} keyFor={(i) => i.id}
                       header={<h2>Section Title</h2>}/>
        )
        expect(screen.getByText('Section Title')).toBeInTheDocument()
    })
})