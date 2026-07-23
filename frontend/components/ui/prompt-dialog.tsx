"use client"

import {useEffect, useState} from "react"
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {TextField} from "@/components/ui/form-elements"

interface PromptDialogProps {
    open: boolean
    onOpenChange: (open: boolean) => void
    title: string
    description?: string
    label: string
    defaultValue?: string
    confirmLabel?: string
    onConfirm: (value: string) => void
}

function PromptDialog({
    open,
    onOpenChange,
    title,
    description,
    label,
    defaultValue = "",
    confirmLabel = "Confirm",
    onConfirm,
}: PromptDialogProps) {
    const [value, setValue] = useState(defaultValue)

    useEffect(() => {
        if (open) setValue(defaultValue)
    }, [open, defaultValue])

    return (
        <AlertDialog open={open} onOpenChange={onOpenChange}>
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>{title}</AlertDialogTitle>
                    {description && <AlertDialogDescription>{description}</AlertDialogDescription>}
                </AlertDialogHeader>
                <label htmlFor="prompt-dialog-value" className="block text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-1.5">
                    {label}
                </label>
                <TextField
                    id="prompt-dialog-value"
                    autoFocus
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                    onKeyDown={(e) => {
                        if (e.key === "Enter" && value.trim()) {
                            onConfirm(value)
                            onOpenChange(false)
                        }
                    }}
                />
                <AlertDialogFooter>
                    <AlertDialogCancel>Cancel</AlertDialogCancel>
                    <AlertDialogAction
                        disabled={!value.trim()}
                        onClick={() => {
                            onConfirm(value)
                            onOpenChange(false)
                        }}
                    >
                        {confirmLabel}
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    )
}

export {PromptDialog}
export type {PromptDialogProps}
