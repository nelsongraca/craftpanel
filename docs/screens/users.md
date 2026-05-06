# Users

User list, user detail, and group assignment views.

<style>
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; font-size: 13px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; padding: 7px 14px; font-size: 13px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-active { background: #d4edda; color: #155724; }
.cp-badge-inactive { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-global { background: #e8f0fe; color: #1a56db; }
.cp-badge-scoped { background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); }
.cp-badge-group { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-row { display: grid; align-items: center; padding: 11px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 12px; font-size: 13px; }
.cp-row:last-child { border-bottom: none; }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-select { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-avatar { width: 30px; height: 30px; border-radius: 50%; background: var(--md-primary-fg-color); color: white; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: bold; flex-shrink: 0; }
.cp-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- ── USER LIST ──────────────────────── -->
  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
    <div>
      <div style="font-size: 20px; font-weight: bold;">Users</div>
      <div style="font-size: 12px;" class="cp-muted">4 users · 3 active</div>
    </div>
    <button class="cp-btn cp-btn-primary">+ New User</button>
  </div>

  <div class="cp-panel">
    <div style="display:grid;grid-template-columns:36px 2fr 1.5fr 1fr 1fr 100px;padding:7px 14px;font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;color:var(--md-default-fg-color--light);border-bottom:1px solid var(--md-default-fg-color--lightest);">
      <span></span><span>User</span><span>Groups</span><span>Status</span><span>Created</span><span></span>
    </div>

    <div class="cp-row" style="grid-template-columns:36px 2fr 1.5fr 1fr 1fr 100px;">
      <div class="cp-avatar">A</div>
      <div>
        <div style="font-weight:bold;">admin</div>
        <div style="font-size:11px;" class="cp-muted">admin@example.com</div>
      </div>
      <div style="display:flex;gap:4px;flex-wrap:wrap;">
        <span class="cp-badge" style="background:#fdecea;color:#721c24;">Super Admin</span>
      </div>
      <span class="cp-badge cp-badge-active">● Active</span>
      <span style="font-size:12px;" class="cp-muted">2026-01-01</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <div class="cp-row" style="grid-template-columns:36px 2fr 1.5fr 1fr 1fr 100px;">
      <div class="cp-avatar" style="background:#2980B9;">J</div>
      <div>
        <div style="font-weight:bold;">jsmith</div>
        <div style="font-size:11px;" class="cp-muted">j@example.com</div>
      </div>
      <div style="display:flex;gap:4px;flex-wrap:wrap;">
        <span class="cp-badge cp-badge-group">Server Admin</span>
        <span class="cp-badge cp-badge-group">Operator</span>
      </div>
      <span class="cp-badge cp-badge-active">● Active</span>
      <span style="font-size:12px;" class="cp-muted">2026-02-14</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <div class="cp-row" style="grid-template-columns:36px 2fr 1.5fr 1fr 1fr 100px;">
      <div class="cp-avatar" style="background:#8E44AD;">M</div>
      <div>
        <div style="font-weight:bold;">mwilson</div>
        <div style="font-size:11px;" class="cp-muted">mwilson@example.com</div>
      </div>
      <div style="display:flex;gap:4px;flex-wrap:wrap;">
        <span class="cp-badge cp-badge-group">Viewer</span>
      </div>
      <span class="cp-badge cp-badge-active">● Active</span>
      <span style="font-size:12px;" class="cp-muted">2026-03-05</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <div class="cp-row" style="grid-template-columns:36px 2fr 1.5fr 1fr 1fr 100px;">
      <div class="cp-avatar" style="background:#7F8C8D;">T</div>
      <div>
        <div style="font-weight:bold; " class="cp-muted">tbarker</div>
        <div style="font-size:11px;" class="cp-muted">t@example.com</div>
      </div>
      <div style="display:flex;gap:4px;flex-wrap:wrap;">
        <span class="cp-badge cp-badge-group">Operator</span>
      </div>
      <span class="cp-badge cp-badge-inactive">● Inactive</span>
      <span style="font-size:12px;" class="cp-muted">2026-04-01</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>
  </div>

  <!-- ── USER DETAIL ───────────────────── -->
  <div style="font-size: 16px; font-weight: bold; margin: 32px 0 4px;">User Detail — jsmith</div>
  <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Opened by clicking View on a user row</div>

  <div style="display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:16px;">
    <div style="display:flex;gap:14px;align-items:center;">
      <div class="cp-avatar" style="background:#2980B9;width:48px;height:48px;font-size:20px;">J</div>
      <div>
        <div style="font-size:20px;font-weight:bold;">jsmith</div>
        <div style="font-size:12px;" class="cp-muted">j@example.com · Active · joined 2026-02-14</div>
      </div>
    </div>
    <div style="display:flex;gap:8px;">
      <button class="cp-btn">Reset Password</button>
      <button class="cp-btn">Deactivate</button>
      <button class="cp-btn">···</button>
    </div>
  </div>

  <div class="cp-grid-2">

    <!-- Group assignments -->
    <div class="cp-panel">
      <div class="cp-panel-header">
        <span>Group Assignments</span>
        <button class="cp-btn" style="font-size:12px;">+ Add Assignment</button>
      </div>

      <div class="cp-row" style="grid-template-columns:1fr 1fr 36px;">
        <div>
          <div style="font-weight:bold;">Server Admin</div>
          <span class="cp-badge cp-badge-global">GLOBAL</span>
        </div>
        <span class="cp-muted" style="font-size:12px;">All servers and networks</span>
        <button class="cp-btn" style="padding:3px 7px;color:#721c24;border-color:#f5c6cb;">🗑</button>
      </div>

      <div class="cp-row" style="grid-template-columns:1fr 1fr 36px;">
        <div>
          <div style="font-weight:bold;">Operator</div>
          <span class="cp-badge cp-badge-scoped">SERVER</span>
        </div>
        <span style="font-size:12px;">Survival</span>
        <button class="cp-btn" style="padding:3px 7px;color:#721c24;border-color:#f5c6cb;">🗑</button>
      </div>

      <div class="cp-row" style="grid-template-columns:1fr 1fr 36px;">
        <div>
          <div style="font-weight:bold;">Operator</div>
          <span class="cp-badge cp-badge-scoped">NETWORK</span>
        </div>
        <span style="font-size:12px;">Creative Network</span>
        <button class="cp-btn" style="padding:3px 7px;color:#721c24;border-color:#f5c6cb;">🗑</button>
      </div>

      <!-- Add assignment form -->
      <div style="padding:12px 14px;border-top:1px solid var(--md-default-fg-color--lightest);display:flex;flex-direction:column;gap:8px;">
        <div style="font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;" class="cp-muted">Add Assignment</div>
        <select class="cp-select" style="width:100%;">
          <option>Select group…</option>
          <option>Viewer</option>
          <option>Operator</option>
        </select>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;">
          <select class="cp-select">
            <option>GLOBAL</option>
            <option>SERVER</option>
            <option>NETWORK</option>
          </select>
          <select class="cp-select" style="color:var(--md-default-fg-color--light);">
            <option>— scope —</option>
          </select>
        </div>
        <button class="cp-btn cp-btn-primary" style="align-self:flex-end;padding:5px 14px;">Add</button>
      </div>
    </div>

    <!-- Active sessions -->
    <div class="cp-panel">
      <div class="cp-panel-header">
        <span>Active Sessions</span>
        <button class="cp-btn" style="font-size:12px;color:#721c24;border-color:#f5c6cb;">Revoke All</button>
      </div>
      <div class="cp-row" style="grid-template-columns:1fr auto;">
        <div>
          <div style="font-size:13px;">Session #1</div>
          <div style="font-size:11px;" class="cp-muted">Started 2026-05-04 08:00 · expires 2026-06-04</div>
        </div>
        <button class="cp-btn" style="font-size:12px;color:#721c24;border-color:#f5c6cb;">Revoke</button>
      </div>
      <div class="cp-row" style="grid-template-columns:1fr auto;">
        <div>
          <div style="font-size:13px;">Session #2</div>
          <div style="font-size:11px;" class="cp-muted">Started 2026-05-03 14:22 · expires 2026-06-03</div>
        </div>
        <button class="cp-btn" style="font-size:12px;color:#721c24;border-color:#f5c6cb;">Revoke</button>
      </div>
    </div>
  </div>

</div>
