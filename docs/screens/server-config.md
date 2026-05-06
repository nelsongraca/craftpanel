# Server Configuration

Configuration tab on the server detail page. Shows managed env vars or manual mode toggle.

<style>
.cp-tabs { display: flex; border-bottom: 2px solid var(--md-default-fg-color--lightest); margin-bottom: 20px; }
.cp-tab { padding: 8px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color--light); border-bottom: 2px solid transparent; margin-bottom: -2px; }
.cp-tab.active { color: var(--md-primary-fg-color); border-bottom-color: var(--md-primary-fg-color); font-weight: bold; }
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: bold; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; }
.cp-btn-danger { background: #fdecea; color: #721c24; border: 1px solid #f5c6cb; }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-select { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-env-row { display: grid; grid-template-columns: 200px 1fr 36px; gap: 8px; align-items: center; padding: 8px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); }
.cp-env-row:last-child { border-bottom: none; }
.cp-env-key { font-family: monospace; font-size: 13px; color: var(--md-primary-fg-color); }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-toggle { display: flex; align-items: center; gap: 10px; }
.cp-toggle-track { width: 40px; height: 22px; background: var(--md-primary-fg-color); border-radius: 11px; position: relative; cursor: pointer; }
.cp-toggle-thumb { width: 16px; height: 16px; background: white; border-radius: 50%; position: absolute; top: 3px; right: 3px; }
.cp-toggle-track-off { background: var(--md-default-fg-color--lightest); }
.cp-toggle-thumb-off { left: 3px; right: auto; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-managed { background: #e8f0fe; color: #1a56db; }
.cp-badge-manual { background: #fff3e0; color: #856404; }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
    <div>
      <div style="font-size: 12px; margin-bottom: 4px;" class="cp-muted">Servers / Survival Network /</div>
      <span style="font-size: 22px; font-weight: bold;">Survival</span>
    </div>
    <div style="display: flex; gap: 8px;">
      <button class="cp-btn">↺ Restart</button>
      <button class="cp-btn">■ Stop</button>
      <button class="cp-btn">···</button>
    </div>
  </div>

  <div class="cp-tabs">
    <div class="cp-tab">Overview</div>
    <div class="cp-tab">Console</div>
    <div class="cp-tab">Files</div>
    <div class="cp-tab">Mods</div>
    <div class="cp-tab">Backups</div>
    <div class="cp-tab active">Configuration</div>
  </div>

  <!-- Config mode toggle -->
  <div class="cp-panel">
    <div class="cp-panel-header">Config Mode</div>
    <div style="padding: 14px; display: flex; justify-content: space-between; align-items: center;">
      <div>
        <div style="font-weight: bold; margin-bottom: 4px;">Managed Mode <span class="cp-badge cp-badge-managed">ACTIVE</span></div>
        <div style="font-size: 12px;" class="cp-muted">CraftPanel manages env vars and generates the container spec. Switch to Manual to edit config files directly.</div>
      </div>
      <div class="cp-toggle">
        <span style="font-size: 12px;" class="cp-muted">Manual</span>
        <div class="cp-toggle-track"><div class="cp-toggle-thumb"></div></div>
        <span style="font-size: 12px; font-weight: bold;">Managed</span>
      </div>
    </div>
  </div>

  <!-- Stop command -->
  <div class="cp-panel">
    <div class="cp-panel-header">Stop Command</div>
    <div style="padding: 14px; display: flex; gap: 12px; align-items: flex-end;">
      <div style="flex: 1;">
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px;" class="cp-muted">COMMAND SENT TO STDIN ON STOP / RESTART</div>
        <input class="cp-input" value="stop" style="width: 200px;" />
        <div style="font-size: 11px; margin-top: 4px;" class="cp-muted">Default for PAPER: <code>stop</code> · Leave empty to skip and go straight to Docker stop</div>
      </div>
      <button class="cp-btn cp-btn-primary">Save</button>
    </div>
  </div>

  <!-- Env vars -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Environment Variables</span>
      <div style="display:flex;gap:8px;">
        <button class="cp-btn">+ Add Variable</button>
        <button class="cp-btn cp-btn-primary">Save All</button>
      </div>
    </div>
    <div style="display: grid; grid-template-columns: 200px 1fr 36px; padding: 7px 14px; font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.4px; color: var(--md-default-fg-color--light); border-bottom: 1px solid var(--md-default-fg-color--lightest);">
      <span>Key</span><span>Value</span><span></span>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">TYPE</span>
      <input class="cp-input" value="PAPER" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">VERSION</span>
      <input class="cp-input" value="1.21.4" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">DIFFICULTY</span>
      <input class="cp-input" value="hard" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">MAX_PLAYERS</span>
      <input class="cp-input" value="50" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">MOTD</span>
      <input class="cp-input" value="Welcome to Survival!" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">MEMORY</span>
      <input class="cp-input" value="4G" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div class="cp-env-row">
      <span class="cp-env-key">EULA</span>
      <input class="cp-input" value="TRUE" style="width:100%; box-sizing:border-box;" />
      <button class="cp-btn" style="padding:4px 8px; color:#721c24; border-color:#f5c6cb;">🗑</button>
    </div>
    <div style="padding: 10px 14px; font-size: 12px; border-top: 1px solid var(--md-default-fg-color--lightest);" class="cp-muted">
      Changes apply on next server restart.
    </div>
  </div>

  <!-- Resources -->
  <div class="cp-panel">
    <div class="cp-panel-header">Resource Allocation</div>
    <div style="padding: 14px; display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: end;">
      <div>
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px;" class="cp-muted">RAM (MB)</div>
        <input class="cp-input" value="4096" style="width: 120px;" />
        <div style="font-size: 11px; margin-top: 4px;" class="cp-muted">Node available: 6,144 MB additional</div>
      </div>
      <div>
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px;" class="cp-muted">CPU SHARES</div>
        <input class="cp-input" value="512" style="width: 120px;" />
        <div style="font-size: 11px; margin-top: 4px;" class="cp-muted">Relative weight — 1024 = one full core</div>
      </div>
    </div>
    <div style="padding: 0 14px 14px; display: flex; justify-content: flex-end; gap: 8px;">
      <button class="cp-btn">Discard</button>
      <button class="cp-btn cp-btn-primary">Save Resources</button>
    </div>
  </div>

</div>
