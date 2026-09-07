"use client";

import {useEffect, useRef, useState} from "react";
import {SelectField, TextField} from "@/components/ui/form-elements";
import {Skeleton} from "@/components/ui/skeleton";
import {fetchReleaseVersions} from "@/lib/utils/format";

interface McVersionSelectProps {
    id?: string;
    value: string;
    onChange: (next: string) => void;
    placeholder?: string;
    required?: boolean;
    disabled?: boolean;
    fieldSize?: "sm" | "md";
    surface?: "bg" | "surface" | "surface-high" | "surface-higher";
    className?: string;
    /** Called once with the loaded Mojang release versions (empty if the manifest could not be fetched). */
    onLoaded?: (versions: string[]) => void;
}

export function McVersionSelect({
                                    id,
                                    value,
                                    onChange,
                                    placeholder,
                                    required,
                                    disabled,
                                    fieldSize = "md",
                                    surface = "surface",
                                    className,
                                    onLoaded,
                                }: McVersionSelectProps) {
    const [versions, setVersions] = useState<string[] | null>(null);
    const onLoadedRef = useRef(onLoaded);

    useEffect(() => {
        onLoadedRef.current = onLoaded;
    });

    useEffect(() => {
        let active = true;
        fetchReleaseVersions()
            .catch(() => [])
            .then((vs) => {
                if (!active) return;
                setVersions(vs);
                onLoadedRef.current?.(vs);
            });
        return () => {
            active = false;
        };
    }, []);

    if (versions === null) {
        return <Skeleton className={`h-9 bg-surface-high${className ? ` ${className}` : ""}`}/>;
    }
    if (versions.length > 0) {
        return (
            <SelectField
                id={id}
                value={value}
                onChange={(e) => onChange(e.target.value)}
                disabled={disabled}
                required={required}
                fieldSize={fieldSize}
                surface={surface}
                className={className}
            >
                {versions.map((v) => <option key={v} value={v}>{v}</option>)}
            </SelectField>
        );
    }
    return (
        <TextField
            id={id}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            placeholder={placeholder}
            disabled={disabled}
            required={required}
            fieldSize={fieldSize}
            surface={surface}
            className={className}
        />
    );
}
