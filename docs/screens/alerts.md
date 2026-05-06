# Alerts

Alert thresholds configuration and event log.

<style>
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; font-size: 13px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; padding: 7px 14px; font-size: 13px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-active { background: #fdecea; color: #721c24; }
.cp-badge-resolved { background: #d4edda; color: #155724; }
.cp-badge-node { background: #e8f0fe; color: #1a56db; }
.cp-badge-server { background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-row { display: grid; align-items: center; padding: 11px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 12px; font-size: 13px; }
.cp-row:last-child { border-bottom: none; }
.cp-select { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-alert-fired { border-left: 3px solid #E74C3C; background: #fdecea; }
.cp-alert-warn { border-left: 3px solid #E67E22; background: #fff3e0; }
.cp-alert-resolved { border-left: 3px solid #27AE60; background: var(--md-default-bg-color); }
.cp-filters { display: flex; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
    <div>
      <div style="font-size: 20px; font-weight: bold;">Alerts</div>
      <div style="font-size: 12px;" class="cp-muted">2 active · 1 resolved today</div>
    </div>
  </div>

  <!-- Active alerts summary -->
  <div style="margin-bottom: 20px;">
    <div style="font-size: 13px; font-weight: bold; margin-bottom: 10px;">Active Now</div>
    <div style="border: 1px solid #f5c6cb; border-radius: 6px; overflow: hidden;">
      <div style="display:grid;grid-template-columns:1fr auto;align-items:center;padding:12px 14px;border-bottom:1px solid #f5c6cb;gap:12px;" class="cp-alert-fired">
        <div>
          <div style="font-weight:bold;font-size:13px;">🔴 Skyblock — server UNHEALTHY</div>
          <div style="font-size:12px;color:#721c24;">Fired 14 minutes ago · 10:46:03</div>
        </div>
        <div style="display:flex;gap:6px;">
          <button class="cp-btn" style="font-size:12px;">View Server</button>
        </div>
      </div>
      <div style="display:grid;grid-template-columns:1fr auto;align-items:center;padding:12px 14px;gap:12px;" class="cp-alert-warn">
        <div>
          <div style="font-weight:bold;font-size:13px;">🟠 node-1 — RAM at 93% (threshold: 90%)</div>
          <div style="font-size:12px;color:#856404;">Fired 9 minutes ago · 10:51:22</div>
        </div>
        <div style="display:flex;gap:6px;">
          <button class="cp-btn" style="font-size:12px;">View Node</button>
        </div>
      </div>
    </div>
  </div>

  <!-- Thresholds -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Alert Thresholds</span>
      <button class="cp-btn cp-btn-primary" style="padding:5px 12px;font-size:12px;">+ New Threshold</button>
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr 1.2fr 80px 80px;padding:7px 14px;font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;color:var(--md-default-fg-color--light);border-bottom:1px solid var(--md-default-fg-color--lightest);">
      <span>Scope</span><span>Target</span><span>Condition</span><span>Type</span><span></span>
    </div>

    <div class="cp-row" style="grid-template-columns:1fr 1fr 1.2fr 80px 80px;">
      <span class="cp-badge cp-badge-node">NODE</span>
      <span>node-1</span>
      <span>ram_percent &gt; <strong>90</strong></span>
      <span style="font-size:12px;" class="cp-muted">numeric</span>
      <button class="cp-btn" style="padding:3px 8px;color:#721c24;border-color:#f5c6cb;">🗑</button>
    </div>

    <div class="cp-row" style="grid-template-columns:1fr 1fr 1.2fr 80px 80px;">
      <span class="cp-badge cp-badge-node">NODE</span>
      <span>node-2</span>
      <span>ram_percent &gt; <strong>90</strong></span>
      <span style="font-size:12px;" class="cp-muted">numeric</span>
      <button class="cp-btn" style="padding:3px 8px;color:#721c24;border-color:#f5c6cb;">🗑</button>
    </div>

    <div class="cp-row" style="grid-template-columns:1fr 1fr 1.2fr 80px 80px;">
      <span class="cp-badge cp-badge-server">SERVER</span>
      <span>Skyblock</span>
      <span>server_health = <strong>UNHEALTHY</strong></span>
      <span style="font-size:12px;" class="cp-muted">state</span>
      <button class="cp-btn" style="padding:3px 8px;color:#721c24;border-color:#f5c6cb;">🗑</button>
    </div>

    <div class="cp-row" style="grid-template-columns:1fr 1fr 1.2fr 80px 80px;">
      <span class="cp-badge cp-badge-server">SERVER</span>
      <span>Survival</span>
      <span>cpu_percent &gt; <strong>80</strong></span>
      <span style="font-size:12px;" class="cp-muted">numeric</span>
      <button class="cp-btn" style="padding:3px 8px;color:#721c24;border-color:#f5c6cb;">🗑</button>
    </div>
  </div>

  <!-- New threshold form -->
  <div class="cp-panel">
    <div class="cp-panel-header">New Threshold</div>
    <div style="padding:14px;display:grid;grid-template-columns:1fr 1fr 1fr 1fr;gap:12px;align-items:end;">
      <div>
        <div style="font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;margin-bottom:6px;" class="cp-muted">Scope Type</div>
        <select class="cp-select" style="width:100%;">
          <option>NODE</option>
          <option>SERVER</option>
        </select>
      </div>
      <div>
        <div style="font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;margin-bottom:6px;" class="cp-muted">Target</div>
        <select class="cp-select" style="width:100%;">
          <option>node-1</option>
          <option>node-2</option>
        </select>
      </div>
      <div>
        <div style="font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;margin-bottom:6px;" class="cp-muted">Metric</div>
        <select class="cp-select" style="width:100%;">
          <option>ram_percent</option>
          <option>cpu_percent</option>
          <option>disk_percent</option>
          <option>server_health</option>
        </select>
      </div>
      <div>
        <div style="font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;margin-bottom:6px;" class="cp-muted">Threshold Value</div>
        <input class="cp-input" placeholder="e.g. 90" style="width:100%;box-sizing:border-box;" />
      </div>
    </div>
    <div style="padding:0 14px 14px;display:flex;justify-content:flex-end;">
      <button class="cp-btn cp-btn-primary" style="padding:6px 14px;">Add Threshold</button>
    </div>
  </div>

  <!-- Event log -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Event Log</span>
      <div style="display:flex;gap:8px;">
        <select class="cp-select" style="font-size:12px;padding:4px 8px;">
          <option>All scopes</option>
          <option>Nodes only</option>
          <option>Servers only</option>
        </select>
        <select class="cp-select" style="font-size:12px;padding:4px 8px;">
          <option>All events</option>
          <option>Active only</option>
          <option>Resolved only</option>
        </select>
      </div>
    </div>

    <div style="display:grid;grid-template-columns:1fr 1fr 100px 120px;padding:7px 14px;font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;color:var(--md-default-fg-color--light);border-bottom:1px solid var(--md-default-fg-color--lightest);">
      <span>Message</span><span>Fired</span><span>Resolved</span><span>Status</span>
    </div>

    <div class="cp-row cp-alert-fired" style="grid-template-columns:1fr 1fr 100px 120px;">
      <span><strong>Skyblock</strong> — server UNHEALTHY</span>
      <span style="font-size:12px;">10:46:03 · 14 min ago</span>
      <span class="cp-muted" style="font-size:12px;">—</span>
      <span class="cp-badge cp-badge-active">● ACTIVE</span>
    </div>

    <div class="cp-row cp-alert-warn" style="grid-template-columns:1fr 1fr 100px 120px;">
      <span><strong>node-1</strong> — RAM at 93%</span>
      <span style="font-size:12px;">10:51:22 · 9 min ago</span>
      <span class="cp-muted" style="font-size:12px;">—</span>
      <span class="cp-badge cp-badge-active">● ACTIVE</span>
    </div>

    <div class="cp-row cp-alert-resolved" style="grid-template-columns:1fr 1fr 100px 120px;">
      <span><strong>Survival</strong> — CPU at 84%</span>
      <span style="font-size:12px;">09:12:00</span>
      <span style="font-size:12px;">09:18:44</span>
      <span class="cp-badge cp-badge-resolved">● RESOLVED</span>
    </div>

    <div class="cp-row cp-alert-resolved" style="grid-template-columns:1fr 1fr 100px 120px;">
      <span><strong>node-1</strong> — RAM at 91%</span>
      <span style="font-size:12px;">2026-05-03 22:05</span>
      <span style="font-size:12px;">22:31:10</span>
      <span class="cp-badge cp-badge-resolved">● RESOLVED</span>
    </div>

  </div>

</div>
