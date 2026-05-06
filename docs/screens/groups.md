# Groups

Group list and permission editor.

<style>
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; font-size: 13px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; padding: 7px 14px; font-size: 13px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-system { background: #fff3e0; color: #856404; }
.cp-badge-custom { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-perm { display: inline-block; padding: 2px 7px; border-radius: 10px; font-size: 11px; background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); margin: 2px; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-row { display: grid; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 12px; font-size: 13px; }
.cp-row:last-child { border-bottom: none; }
.cp-perm-section { padding: 10px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); }
.cp-perm-section:last-child { border-bottom: none; }
.cp-perm-section-title { font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.5px; color: var(--md-default-fg-color--light); margin-bottom: 10px; }
.cp-perm-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.cp-perm-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; font-size: 12px; cursor: pointer; }
.cp-perm-item.checked { background: var(--md-primary-fg-color--transparent, #E8F5EE); border-color: var(--md-primary-fg-color); color: var(--md-primary-fg-color); }
.cp-perm-item.checked .cp-checkbox { background: var(--md-primary-fg-color); border-color: var(--md-primary-fg-color); color: white; }
.cp-checkbox { width: 16px; height: 16px; border: 1px solid var(--md-default-fg-color--lightest); border-radius: 3px; display: flex; align-items: center; justify-content: center; font-size: 10px; flex-shrink: 0; }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- ── GROUP LIST ─────────────────────── -->
  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
    <div>
      <div style="font-size: 20px; font-weight: bold;">Groups</div>
      <div style="font-size: 12px;" class="cp-muted">4 groups · 3 system · 1 custom</div>
    </div>
    <button class="cp-btn cp-btn-primary">+ New Group</button>
  </div>

  <div class="cp-panel">
    <div style="display:grid;grid-template-columns:2fr 80px 2fr 1fr 100px;padding:7px 14px;font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;color:var(--md-default-fg-color--light);border-bottom:1px solid var(--md-default-fg-color--lightest);">
      <span>Group</span><span>Type</span><span>Permissions</span><span>Members</span><span></span>
    </div>

    <div class="cp-row" style="grid-template-columns:2fr 80px 2fr 1fr 100px;">
      <div>
        <div style="font-weight:bold;">Super Admin</div>
        <div style="font-size:11px;" class="cp-muted">Full access to all features</div>
      </div>
      <span class="cp-badge cp-badge-system">system</span>
      <div>
        <span class="cp-badge-perm">all permissions</span>
      </div>
      <span>1 user</span>
      <button class="cp-btn">View</button>
    </div>

    <div class="cp-row" style="grid-template-columns:2fr 80px 2fr 1fr 100px;">
      <div>
        <div style="font-weight:bold;">Server Admin</div>
        <div style="font-size:11px;" class="cp-muted">Manage servers, no system access</div>
      </div>
      <span class="cp-badge cp-badge-system">system</span>
      <div>
        <span class="cp-badge-perm">server.create</span>
        <span class="cp-badge-perm">server.delete</span>
        <span class="cp-badge-perm">server.configure</span>
        <span class="cp-badge-perm">+9 more</span>
      </div>
      <span>1 user</span>
      <button class="cp-btn">View</button>
    </div>

    <div class="cp-row" style="grid-template-columns:2fr 80px 2fr 1fr 100px;">
      <div>
        <div style="font-weight:bold;">Operator</div>
        <div style="font-size:11px;" class="cp-muted">Start, stop, console access</div>
      </div>
      <span class="cp-badge cp-badge-system">system</span>
      <div>
        <span class="cp-badge-perm">server.start</span>
        <span class="cp-badge-perm">server.stop</span>
        <span class="cp-badge-perm">server.restart</span>
        <span class="cp-badge-perm">server.console</span>
        <span class="cp-badge-perm">server.view</span>
      </div>
      <span>2 users</span>
      <button class="cp-btn">View</button>
    </div>

    <div class="cp-row" style="grid-template-columns:2fr 80px 2fr 1fr 100px;">
      <div>
        <div style="font-weight:bold;">Viewer</div>
        <div style="font-size:11px;" class="cp-muted">Read-only access</div>
      </div>
      <span class="cp-badge cp-badge-system">system</span>
      <div>
        <span class="cp-badge-perm">server.view</span>
      </div>
      <span>1 user</span>
      <button class="cp-btn">View</button>
    </div>

    <div class="cp-row" style="grid-template-columns:2fr 80px 2fr 1fr 100px;">
      <div>
        <div style="font-weight:bold;">Moderators</div>
        <div style="font-size:11px;" class="cp-muted">Custom group</div>
      </div>
      <span class="cp-badge cp-badge-custom">custom</span>
      <div>
        <span class="cp-badge-perm">server.console</span>
        <span class="cp-badge-perm">server.view</span>
        <span class="cp-badge-perm">server.files</span>
      </div>
      <span>0 users</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>
  </div>

  <!-- ── GROUP DETAIL / PERMISSION EDITOR ── -->
  <div style="font-size: 16px; font-weight: bold; margin: 32px 0 4px;">Group Detail — Server Admin</div>
  <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Permission editor opened by clicking View on a group</div>

  <div style="display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:16px;">
    <div>
      <div style="display:flex;align-items:center;gap:10px;">
        <span style="font-size:20px;font-weight:bold;">Server Admin</span>
        <span class="cp-badge cp-badge-system">system</span>
      </div>
      <div style="font-size:12px;margin-top:3px;" class="cp-muted">1 user assigned · system group — name cannot be changed</div>
    </div>
    <button class="cp-btn cp-btn-primary" style="padding:6px 14px;">Save Permissions</button>
  </div>

  <div class="cp-panel">
    <div class="cp-panel-header">Permissions</div>

    <!-- System permissions -->
    <div class="cp-perm-section">
      <div class="cp-perm-section-title">System</div>
      <div class="cp-perm-grid">
        <div class="cp-perm-item">
          <div class="cp-checkbox"></div>
          <span>system.settings</span>
        </div>
        <div class="cp-perm-item">
          <div class="cp-checkbox"></div>
          <span>system.users</span>
        </div>
        <div class="cp-perm-item">
          <div class="cp-checkbox"></div>
          <span>system.nodes</span>
        </div>
      </div>
    </div>

    <!-- Server permissions -->
    <div class="cp-perm-section">
      <div class="cp-perm-section-title">Server — Lifecycle</div>
      <div class="cp-perm-grid">
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.create</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.delete</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.start</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.stop</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.restart</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.upgrade</span>
        </div>
      </div>
    </div>

    <div class="cp-perm-section">
      <div class="cp-perm-section-title">Server — Management</div>
      <div class="cp-perm-grid">
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.configure</span>
        </div>
        <div class="cp-perm-item">
          <div class="cp-checkbox"></div>
          <span>server.resources</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.files</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.mods</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.console</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.view</span>
        </div>
      </div>
    </div>

    <div class="cp-perm-section">
      <div class="cp-perm-section-title">Server — Operations</div>
      <div class="cp-perm-grid">
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.backup</span>
        </div>
        <div class="cp-perm-item checked">
          <div class="cp-checkbox">✓</div>
          <span>server.export</span>
        </div>
        <div class="cp-perm-item">
          <div class="cp-checkbox"></div>
          <span>server.migrate</span>
        </div>
      </div>
    </div>

  </div>

  <!-- Members -->
  <div class="cp-panel">
    <div class="cp-panel-header">Members with this Group (1)</div>
    <div class="cp-row" style="grid-template-columns:2fr 1fr 1fr;">
      <div style="display:flex;align-items:center;gap:10px;">
        <div style="width:28px;height:28px;border-radius:50%;background:#2980B9;display:flex;align-items:center;justify-content:center;color:white;font-size:12px;font-weight:bold;">J</div>
        <div>
          <div style="font-weight:bold;">jsmith</div>
          <div style="font-size:11px;" class="cp-muted">j@example.com</div>
        </div>
      </div>
      <span class="cp-badge" style="background:#e8f0fe;color:#1a56db;">GLOBAL</span>
      <button class="cp-btn" style="justify-self:end;">View User</button>
    </div>
  </div>

</div>
