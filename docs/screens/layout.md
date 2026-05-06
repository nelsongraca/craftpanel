# Layout

The overall page shell used across all CraftPanel screens. All other mockups show only the content area inside this shell.

<style>
.cp-nav { background: var(--md-primary-fg-color); color: var(--md-primary-bg-color); padding: 12px 20px; display: flex; justify-content: space-between; align-items: center; border-radius: 6px 6px 0 0; }
.cp-nav-brand { font-size: 17px; font-weight: bold; }
.cp-nav-links { display: flex; gap: 20px; font-size: 13px; opacity: 0.85; }
.cp-nav-links span { cursor: pointer; }
.cp-nav-links span.active { opacity: 1; font-weight: bold; border-bottom: 2px solid var(--md-primary-bg-color); padding-bottom: 2px; }
.cp-shell { display: grid; grid-template-columns: 200px 1fr; border: 1px solid var(--md-default-fg-color--lightest); border-top: none; border-radius: 0 0 6px 6px; overflow: hidden; font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); }
.cp-sidebar { background: var(--md-default-bg-color); border-right: 1px solid var(--md-default-fg-color--lightest); padding: 16px 0; min-height: 400px; }
.cp-sidebar-section { font-size: 10px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.8px; color: var(--md-default-fg-color--light); padding: 12px 16px 4px; }
.cp-sidebar-item { padding: 7px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color); display: flex; align-items: center; gap: 8px; }
.cp-sidebar-item:hover { background: var(--md-default-fg-color--lightest); }
.cp-sidebar-item.active { background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); font-weight: bold; border-left: 3px solid var(--md-primary-fg-color); }
.cp-content { background: var(--md-default-bg-color); padding: 24px; }
.cp-page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.cp-page-title { font-size: 20px; font-weight: bold; }
.cp-page-sub { font-size: 12px; color: var(--md-default-fg-color--light); margin-top: 3px; }
.cp-btn { border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; font-size: 13px; }
.cp-btn-primary { background: var(--md-primary-fg-color); color: var(--md-primary-bg-color); }
.cp-btn-secondary { background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest) !important; }
.cp-content-placeholder { border: 2px dashed var(--md-default-fg-color--lightest); border-radius: 6px; padding: 40px; text-align: center; color: var(--md-default-fg-color--light); font-size: 13px; }
</style>

<div style="font-family: Arial, sans-serif; max-width: 960px;">

  <div class="cp-nav">
    <span class="cp-nav-brand">⛏ CraftPanel</span>
    <div class="cp-nav-links">
      <span>Dashboard</span>
      <span class="active">Servers</span>
      <span>Networks</span>
      <span>Nodes</span>
      <span>Alerts</span>
      <span>Settings</span>
      <span>admin ▾</span>
    </div>
  </div>

  <div class="cp-shell">
    <div class="cp-sidebar">
      <div class="cp-sidebar-section">Servers</div>
      <div class="cp-sidebar-item active">⬛ All Servers</div>
      <div class="cp-sidebar-item">🌐 Networks</div>
      <div class="cp-sidebar-section">Infrastructure</div>
      <div class="cp-sidebar-item">🖥 Nodes</div>
      <div class="cp-sidebar-section">System</div>
      <div class="cp-sidebar-item">🔔 Alerts</div>
      <div class="cp-sidebar-item">👥 Users</div>
      <div class="cp-sidebar-item">🔑 Groups</div>
      <div class="cp-sidebar-item">⚙️ Settings</div>
    </div>
    <div class="cp-content">
      <div class="cp-page-header">
        <div>
          <div class="cp-page-title">Page Title</div>
          <div class="cp-page-sub">Subtitle or breadcrumb</div>
        </div>
        <button class="cp-btn cp-btn-primary">+ Primary Action</button>
      </div>
      <div class="cp-content-placeholder">
        Content area — see individual screen mockups
      </div>
    </div>
  </div>

</div>
