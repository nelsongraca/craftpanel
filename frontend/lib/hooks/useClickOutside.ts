"use client";

import {useEffect, useRef} from "react";

export function useClickOutside(onClick: () => void) {
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        function handle(e: MouseEvent) {
            if (ref.current && !ref.current.contains(e.target as Node)) {
                onClick();
            }
        }
        document.addEventListener("mousedown", handle);
        return () => document.removeEventListener("mousedown", handle);
    }, [onClick]);

    return ref;
}
