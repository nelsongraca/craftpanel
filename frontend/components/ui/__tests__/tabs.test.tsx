import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {Tabs, TabsList, TabsTrigger, TabsContent} from '../tabs'

describe('Tabs', () => {
    it('renders trigger and content', () => {
        render(
            <Tabs defaultValue="tab1">
                <TabsList>
                    <TabsTrigger value="tab1">First</TabsTrigger>
                    <TabsTrigger value="tab2">Second</TabsTrigger>
                </TabsList>
                <TabsContent value="tab1">Content One</TabsContent>
                <TabsContent value="tab2">Content Two</TabsContent>
            </Tabs>,
        )
        expect(screen.getByText('First')).toBeInTheDocument()
        expect(screen.getByText('Second')).toBeInTheDocument()
        expect(screen.getByText('Content One')).toBeInTheDocument()
    })

    it('sets data-slot attributes', () => {
        render(
            <Tabs defaultValue="a">
                <TabsList>
                    <TabsTrigger value="a">A</TabsTrigger>
                </TabsList>
                <TabsContent value="a">Content</TabsContent>
            </Tabs>,
        )
        expect(screen.getByText('A')).toHaveAttribute('data-slot', 'tabs-trigger')
    })

    it('applies default variant to TabsList', () => {
        render(
            <Tabs defaultValue="a">
                <TabsList data-testid="list">
                    <TabsTrigger value="a">A</TabsTrigger>
                </TabsList>
            </Tabs>,
        )
        expect(screen.getByTestId('list').className).toContain('bg-muted')
    })
})