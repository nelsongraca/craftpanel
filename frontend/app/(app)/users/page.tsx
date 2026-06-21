"use client";

import {useCallback, useEffect, useState} from "react";
import {Pencil, Plus, Trash2, Users2} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {createAssignment, createUser, deleteAssignment, deleteUser, listGroups, listNetworks, listServers, listUserAssignments, listUsers, updateUser,} from "@/lib/generated/sdk.gen";
import type {Assignment, Group, User} from "@/lib/types";
import {INPUT, BTN_PRIMARY, BTN_GHOST, Modal, Field} from "@/components/ui/form-elements";


// ── Create User Modal ─────────────────────────────────────────────────────────

function CreateUserModal({onClose, onDone}: { onClose: () => void; onDone: () => void }) {
    const [form, setForm] = useState({username: "", email: "", password: ""});
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        setSaving(true);
        const {error} = await createUser({body: form});
        setSaving(false);
        if (error) {
            setError(error.message ?? "Failed to create user");
            return;
        }
        onDone();
    }

    return (
        <Modal title="Create User" onClose={onClose}>
            <form onSubmit={handleSubmit} className="space-y-4">
                <Field label="Username">
                    <input className={INPUT} value={form.username} onChange={(e) => setForm((f) => ({...f, username: e.target.value}))} required/>
                </Field>
                <Field label="Email">
                    <input className={INPUT} type="email" value={form.email} onChange={(e) => setForm((f) => ({...f, email: e.target.value}))} required/>
                </Field>
                <Field label="Password">
                    <input className={INPUT} type="password" value={form.password} onChange={(e) => setForm((f) => ({...f, password: e.target.value}))} required/>
                </Field>
                {error && <p className="text-[12px] text-error">{error}</p>}
                <div className="flex justify-end gap-2 pt-1">
                    <button type="button" className={BTN_GHOST} onClick={onClose}>Cancel</button>
                    <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Creating…" : "Create"}</button>
                </div>
            </form>
        </Modal>
    );
}

// ── Edit User Modal ───────────────────────────────────────────────────────────

function EditUserModal({user, onClose, onDone}: { user: User; onClose: () => void; onDone: () => void }) {
    const [form, setForm] = useState({
        username: user.username,
        email: user.email,
        isActive: user.is_active,
    });
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        setSaving(true);
        const {error} = await updateUser({
            path: {id: user.id},
            body: {username: form.username, email: form.email, is_active: form.isActive},
        });
        setSaving(false);
        if (error) {
            setError(error.message ?? "Failed to update user");
            return;
        }
        onDone();
    }

    return (
        <Modal title="Edit User" onClose={onClose}>
            <form onSubmit={handleSubmit} className="space-y-4">
                <Field label="Username">
                    <input className={INPUT} value={form.username} onChange={(e) => setForm((f) => ({...f, username: e.target.value}))} required/>
                </Field>
                <Field label="Email">
                    <input className={INPUT} type="email" value={form.email} onChange={(e) => setForm((f) => ({...f, email: e.target.value}))} required/>
                </Field>
                <Field label="Active">
                    <label className="flex items-center gap-2 cursor-pointer">
                        <input
                            type="checkbox"
                            checked={form.isActive}
                            onChange={(e) => setForm((f) => ({...f, isActive: e.target.checked}))}
                            className="accent-amber-500"
                        />
                        <span className="text-[13px] text-text-dim">User is active</span>
                    </label>
                </Field>
                {error && <p className="text-[12px] text-error">{error}</p>}
                <div className="flex justify-end gap-2 pt-1">
                    <button type="button" className={BTN_GHOST} onClick={onClose}>Cancel</button>
                    <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Saving…" : "Save"}</button>
                </div>
            </form>
        </Modal>
    );
}

// ── Assignments Modal ─────────────────────────────────────────────────────────

function AssignmentsModal({
                              user, groups, onClose,
                          }: {
    user: User;
    groups: Group[];
    onClose: () => void;
}) {
    const [assignments, setAssignments] = useState<Assignment[]>([]);
    const [loading, setLoading] = useState(true);
    const [newGroup, setNewGroup] = useState("");
    const [newScope, setNewScope] = useState("GLOBAL");
    const [newScopeId, setNewScopeId] = useState("");
    const [servers, setServers] = useState<{ id: string; display_name: string }[]>([]);
    const [networks, setNetworks] = useState<{ id: string; name: string }[]>([]);
    const [addError, setAddError] = useState("");

    const loadAssignments = useCallback(async () => {
        const res = await listUserAssignments({path: {userId: user.id}});
        if (res.data) setAssignments(res.data.assignments);
        setLoading(false);
    }, [user.id]);

    useEffect(() => {
        loadAssignments();
        listServers().then((r) => {
            if (r.data) setServers(r.data);
        });
        listNetworks().then((r) => {
            if (r.data) setNetworks(r.data);
        });
    }, [loadAssignments]);

    async function handleAdd() {
        setAddError("");
        if (!newGroup) {
            setAddError("Select a group");
            return;
        }
        if (newScope !== "GLOBAL" && !newScopeId) {
            setAddError("Select a scope target");
            return;
        }
        const {error} = await createAssignment({
            path: {userId: user.id},
            body: {group_id: newGroup, scope_type: newScope, scope_id: newScope !== "GLOBAL" ? newScopeId : undefined},
        });
        if (error) {
            setAddError(error.message ?? "Failed to add assignment");
            return;
        }
        setNewGroup("");
        setNewScope("GLOBAL");
        setNewScopeId("");
        loadAssignments();
    }

    async function handleRemove(assignmentId: string) {
        await deleteAssignment({path: {userId: user.id, assignmentId}});
        loadAssignments();
    }

    function groupName(id: string) {
        return groups.find((g) => g.id === id)?.name ?? id.slice(0, 8);
    }

    function scopeLabel(a: Assignment) {
        if (a.scope_type === "GLOBAL") return "Global";
        const target = a.scope_type === "SERVER"
            ? servers.find((s) => s.id === a.scope_id)?.display_name
            : networks.find((n) => n.id === a.scope_id)?.name;
        return `${a.scope_type}: ${target ?? a.scope_id?.slice(0, 8) ?? "—"}`;
    }

    return (
        <Modal title={`Groups — ${user.username}`} onClose={onClose}>
            <div className="space-y-5">
                {/* Current assignments */}
                <div>
                    <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">Current Assignments</p>
                    {loading ? (
                        <p className="text-[12px] text-text-muted">Loading…</p>
                    ) : assignments.length === 0 ? (
                        <p className="text-[12px] text-text-muted">No assignments.</p>
                    ) : (
                        <ul className="space-y-1">
                            {assignments.map((a) => (
                                <li key={a.id} className="flex items-center justify-between bg-surface-high rounded px-3 py-2">
                                    <div>
                                        <span className="text-[12px] text-text-primary">{groupName(a.group_id)}</span>
                                        <span className="ml-2 text-[12px] text-text-muted">{scopeLabel(a)}</span>
                                    </div>
                                    <button onClick={() => handleRemove(a.id)} className="text-text-muted hover:text-error transition-colors ml-2">
                                        <Trash2 size={13}/>
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>

                {/* Add assignment */}
                <div className="border-t border-border pt-4 space-y-3">
                    <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Add Assignment</p>
                    <Field label="Group">
                        <select className={INPUT} value={newGroup} onChange={(e) => setNewGroup(e.target.value)}>
                            <option value="">Select…</option>
                            {groups.map((g) => (
                                <option key={g.id} value={g.id}>{g.name}</option>
                            ))}
                        </select>
                    </Field>
                    <Field label="Scope">
                        <select className={INPUT} value={newScope} onChange={(e) => {
                            setNewScope(e.target.value);
                            setNewScopeId("");
                        }}>
                            <option value="GLOBAL">Global</option>
                            <option value="SERVER">Server</option>
                            <option value="NETWORK">Network</option>
                        </select>
                    </Field>
                    {newScope === "SERVER" && (
                        <Field label="Server">
                            <select className={INPUT} value={newScopeId} onChange={(e) => setNewScopeId(e.target.value)}>
                                <option value="">Select…</option>
                                {servers.map((s) => <option key={s.id} value={s.id}>{s.display_name}</option>)}
                            </select>
                        </Field>
                    )}
                    {newScope === "NETWORK" && (
                        <Field label="Network">
                            <select className={INPUT} value={newScopeId} onChange={(e) => setNewScopeId(e.target.value)}>
                                <option value="">Select…</option>
                                {networks.map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}
                            </select>
                        </Field>
                    )}
                    {addError && <p className="text-[12px] text-error">{addError}</p>}
                    <button className={BTN_PRIMARY} onClick={handleAdd}>Add</button>
                </div>
            </div>
        </Modal>
    );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function UsersPage() {
    const [users, setUsers] = useState<User[]>([]);
    const [groups, setGroups] = useState<Group[]>([]);
    const [loading, setLoading] = useState(true);
    const [showCreate, setShowCreate] = useState(false);
    const [editing, setEditing] = useState<User | null>(null);
    const [managingGroups, setManagingGroups] = useState<User | null>(null);
    const [deleting, setDeleting] = useState<User | null>(null);
    const [deleteError, setDeleteError] = useState("");

    const load = useCallback(async () => {
        const [uRes, gRes] = await Promise.all([listUsers(), listGroups()]);
        if (uRes.data) setUsers(uRes.data.users);
        if (gRes.data) setGroups(gRes.data);
        setLoading(false);
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    async function handleDelete() {
        if (!deleting) return;
        setDeleteError("");
        const {error} = await deleteUser({path: {id: deleting.id}});
        if (error) {
            setDeleteError(error.message ?? "Failed to delete user");
            return;
        }
        setDeleting(null);
        load();
    }

    return (
        <div>
            <PageHeader
                title="Users"
                subtitle="Manage platform users"
                action={
                    <button onClick={() => setShowCreate(true)} className={BTN_PRIMARY + " flex items-center gap-1.5"}>
                        <Plus size={13} strokeWidth={2.5}/>
                        New User
                    </button>
                }
            />

            <div className="p-6">
                {loading ? (
                    <div className="text-[12px] text-text-muted">Loading…</div>
                ) : users.length === 0 ? (
                    <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">No users yet.</div>
                ) : (
                    <div className="bg-surface border border-border rounded-md overflow-hidden">
                        <table className="hidden md:table w-full text-[12px]">
                            <thead>
                            <tr className="border-b border-border">
                                <th className="text-left px-5 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Username</th>
                                <th className="text-left px-4 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Email</th>
                                <th className="text-left px-4 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Status</th>
                                <th className="text-left px-4 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Created</th>
                                <th className="px-4 py-3"/>
                            </tr>
                            </thead>
                            <tbody>
                            {users.map((u) => (
                                <tr key={u.id} className="border-b border-border/50 hover:bg-surface-high/40">
                                    <td className="px-5 py-3 font-medium text-text-primary">{u.username}</td>
                                    <td className="px-4 py-3 text-text-dim">{u.email}</td>
                                    <td className="px-4 py-3">
                      <span
                          className={`inline-flex items-center px-2 py-0.5 rounded text-[12px] font-heading font-bold uppercase tracking-wider border ${u.is_active ? "text-healthy border-healthy/30 bg-healthy/10" : "text-text-muted border-border bg-surface-high"}`}>
                        {u.is_active ? "Active" : "Inactive"}
                      </span>
                                    </td>
                                    <td className="px-4 py-3 text-text-muted font-mono text-[12px]">
                                        {new Date(u.created_at).toLocaleDateString()}
                                    </td>
                                    <td className="px-4 py-3">
                                        <div className="flex items-center gap-1 justify-end">
                                            <button onClick={() => setEditing(u)} className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors"
                                                    title="Edit">
                                                <Pencil size={13}/>
                                            </button>
                                            <button onClick={() => setManagingGroups(u)} className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors"
                                                    title="Manage groups">
                                                <Users2 size={13}/>
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setDeleting(u);
                                                    setDeleteError("");
                                                }}
                                                className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-error transition-colors"
                                                title="Delete"
                                            >
                                                <Trash2 size={13}/>
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>

                        {/* Mobile card list (< md) */}
                        <div className="md:hidden divide-y divide-border">
                            {users.map((u) => (
                                <div key={u.id} className="p-3">
                                    <div className="flex items-start justify-between gap-2">
                                        <div className="min-w-0">
                                            <p className="text-[14px] font-medium text-text-primary truncate">{u.username}</p>
                                            <p className="mt-0.5 text-[12px] text-text-dim truncate">{u.email}</p>
                                            <p className="mt-0.5 font-mono text-[12px] text-text-muted">
                                                Created {new Date(u.created_at).toLocaleDateString()}
                                            </p>
                                        </div>
                                        <span className={`shrink-0 inline-flex items-center px-2 py-0.5 rounded text-[12px] font-heading font-bold uppercase tracking-wider border ${u.is_active ? "text-healthy border-healthy/30 bg-healthy/10" : "text-text-muted border-border bg-surface-high"}`}>
                                            {u.is_active ? "Active" : "Inactive"}
                                        </span>
                                    </div>
                                    <div className="mt-2.5 flex items-center justify-end gap-1">
                                        <button onClick={() => setEditing(u)} className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors" title="Edit">
                                            <Pencil size={15}/>
                                        </button>
                                        <button onClick={() => setManagingGroups(u)} className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors" title="Manage groups">
                                            <Users2 size={15}/>
                                        </button>
                                        <button
                                            onClick={() => {
                                                setDeleting(u);
                                                setDeleteError("");
                                            }}
                                            className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-error transition-colors"
                                            title="Delete"
                                        >
                                            <Trash2 size={15}/>
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
            </div>

            {showCreate && (
                <CreateUserModal onClose={() => setShowCreate(false)} onDone={() => {
                    setShowCreate(false);
                    load();
                }}/>
            )}

            {editing && (
                <EditUserModal user={editing} onClose={() => setEditing(null)} onDone={() => {
                    setEditing(null);
                    load();
                }}/>
            )}

            {managingGroups && (
                <AssignmentsModal user={managingGroups} groups={groups} onClose={() => setManagingGroups(null)}/>
            )}

            {deleting && (
                <Modal title="Delete User" onClose={() => setDeleting(null)}>
                    <p className="text-[13px] text-text-dim mb-4">
                        Delete <span className="text-text-primary font-medium">{deleting.username}</span>? This action cannot be undone.
                    </p>
                    {deleteError && <p className="text-[12px] text-error mb-3">{deleteError}</p>}
                    <div className="flex justify-end gap-2">
                        <button className={BTN_GHOST} onClick={() => setDeleting(null)}>Cancel</button>
                        <button
                            className="px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider bg-error text-bg hover:opacity-90 transition-opacity"
                            onClick={handleDelete}
                        >
                            Delete
                        </button>
                    </div>
                </Modal>
            )}
        </div>
    );
}
