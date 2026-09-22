"use client"

import {useState} from "react"
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

interface ConfirmDialogProps {
    open: boolean
    onOpenChange: (open: boolean) => void
    title: string
    description: string
    confirmLabel?: string
    destructive?: boolean
    /** Runs on confirm. The dialog stays open while it is pending and on throw (showing its message). */
    onConfirm: () => void | Promise<void>
}

function ConfirmDialog({
    open,
    onOpenChange,
    title,
    description,
    confirmLabel = "Confirm",
    destructive = false,
    onConfirm,
}: ConfirmDialogProps) {
    const [pending, setPending] = useState(false)
    const [error, setError] = useState<string | null>(null)

    async function handleConfirm() {
        setPending(true)
        setError(null)
        try {
            await onConfirm()
            onOpenChange(false)
        } catch (e) {
            setError(e instanceof Error ? e.message : "Action failed")
        } finally {
            setPending(false)
        }
    }

    return (
        <AlertDialog
            open={open}
            onOpenChange={(next) => {
                if (pending) return
                setError(null)
                onOpenChange(next)
            }}
        >
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>{title}</AlertDialogTitle>
                    <AlertDialogDescription>{description}</AlertDialogDescription>
                </AlertDialogHeader>
                {error && <p className="text-xs text-error">{error}</p>}
                <AlertDialogFooter>
                    <AlertDialogCancel disabled={pending}>Cancel</AlertDialogCancel>
                    <AlertDialogAction
                        variant={destructive ? "destructive" : "default"}
                        disabled={pending}
                        onClick={() => void handleConfirm()}
                    >
                        {pending ? "Working…" : confirmLabel}
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    )
}

export { ConfirmDialog }
export type { ConfirmDialogProps }
