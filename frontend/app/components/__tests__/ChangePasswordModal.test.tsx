import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const {changePasswordMock} = vi.hoisted(() => ({changePasswordMock: vi.fn()}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: () => ({changePassword: changePasswordMock}),
}));

import {ChangePasswordModal} from "../ChangePasswordModal";

async function fillPasswords(oldPw: string, newPw: string, confirmPw: string) {
    const userEv = userEvent.setup();
    const dialog = screen.getByRole("dialog");
    const inputs = dialog.querySelectorAll<HTMLInputElement>('input[type="password"]');
    await userEv.type(inputs[0], oldPw);
    await userEv.type(inputs[1], newPw);
    await userEv.type(inputs[2], confirmPw);
    return userEv;
}

describe("ChangePasswordModal", () => {
    beforeEach(() => {
        changePasswordMock.mockReset();
    });

    it("calls changePassword with old and new password on submit", async () => {
        changePasswordMock.mockResolvedValue(undefined);
        const onClose = vi.fn();
        render(<ChangePasswordModal onClose={onClose}/>);

        const userEv = await fillPasswords("oldpass", "newpass1", "newpass1");
        await userEv.click(screen.getByRole("button", {name: "Change Password"}));

        await waitFor(() => {
            expect(changePasswordMock).toHaveBeenCalledWith("oldpass", "newpass1");
        });
    });

    it("shows a success message after changing password", async () => {
        changePasswordMock.mockResolvedValue(undefined);
        render(<ChangePasswordModal onClose={vi.fn()}/>);

        const userEv = await fillPasswords("oldpass", "newpass1", "newpass1");
        await userEv.click(screen.getByRole("button", {name: "Change Password"}));

        await waitFor(() => {
            expect(screen.getByText(/password has been changed/i)).toBeInTheDocument();
        });
    });

    it("shows error and does not call API when passwords do not match", async () => {
        render(<ChangePasswordModal onClose={vi.fn()}/>);

        const userEv = await fillPasswords("oldpass", "abc123", "def456");
        await userEv.click(screen.getByRole("button", {name: "Change Password"}));

        expect(screen.getByText("Passwords do not match")).toBeInTheDocument();
        expect(changePasswordMock).not.toHaveBeenCalled();
    });

    it("surfaces API error message", async () => {
        changePasswordMock.mockRejectedValue(new Error("Current password is incorrect"));
        render(<ChangePasswordModal onClose={vi.fn()}/>);

        const userEv = await fillPasswords("wrong", "newpass1", "newpass1");
        await userEv.click(screen.getByRole("button", {name: "Change Password"}));

        await waitFor(() => {
            expect(screen.getByText("Current password is incorrect")).toBeInTheDocument();
        });
    });

    it("calls onClose when done", async () => {
        changePasswordMock.mockResolvedValue(undefined);
        const onClose = vi.fn();
        render(<ChangePasswordModal onClose={onClose}/>);

        const userEv = await fillPasswords("oldpass", "newpass1", "newpass1");
        await userEv.click(screen.getByRole("button", {name: "Change Password"}));

        await waitFor(() => {
            expect(screen.getByRole("button", {name: "Done"})).toBeInTheDocument();
        });

        await userEv.click(screen.getByRole("button", {name: "Done"}));
        expect(onClose).toHaveBeenCalled();
    });
});
