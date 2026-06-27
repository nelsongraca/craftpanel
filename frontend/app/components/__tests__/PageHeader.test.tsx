import {describe, it, expect} from "vitest";
import {render, screen} from "@testing-library/react";
import PageHeader from "../PageHeader";

describe("PageHeader", () => {
    it("renders title", () => {
        render(<PageHeader title="My Title"/>);
        expect(screen.getByText("My Title")).toBeTruthy();
    });

    it("renders subtitle when provided", () => {
        render(<PageHeader title="T" subtitle="A subtitle"/>);
        expect(screen.getByText("A subtitle")).toBeTruthy();
    });

    it("does not render subtitle when omitted", () => {
        const {container} = render(<PageHeader title="T"/>);
        expect(container.querySelector("p")).toBeNull();
    });

    it("renders action when provided", () => {
        render(<PageHeader title="T" action={<button>Click</button>}/>);
        expect(screen.getByRole("button", {name: "Click"})).toBeTruthy();
    });

    it("does not render action wrapper when action is omitted", () => {
        const {container} = render(<PageHeader title="T"/>);
        const cls = container.querySelector(".shrink-0");
        expect(cls).toBeNull();
    });
});
