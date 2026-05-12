# Server Mods

Mods tab on the server detail page.

<style>
.cp-tabs { display: flex; border-bottom: 2px solid var(--md-default-fg-color--lightest); margin-bottom: 20px; }
.cp-tab { padding: 8px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color--light); border-bottom: 2px solid transparent; margin-bottom: -2px; }
.cp-tab.active { color: var(--md-primary-fg-color); border-bottom-color: var(--md-primary-fg-color); font-weight: bold; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-pinned { background: #e8f0fe; color: #1a56db; }
.cp-badge-latest { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-update { background: #fff3e0; color: #856404; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; padding: 7px 14px; font-size: 13px; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-mod-row { display: grid; grid-template-columns: 40px 2fr 1fr 1fr 120px; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 10px; }
.cp-mod-row:last-child { border-bottom: none; }
.cp-mod-icon { width: 32px; height: 32px; background: var(--md-default-fg-color--lightest); border-radius: 6px; display: flex; align-items: center; justify-content: center; font-size: 16px; }
.cp-search-result { display: grid; grid-template-columns: 40px 2fr 1fr 100px; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 10px; }
.cp-search-result:last-child { border-bottom: none; }
.cp-search-result:hover { background: var(--md-default-fg-color--lightest); }
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 8px 14px; display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: bold; }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 7px 12px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); width: 100%; box-sizing: border-box; }
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
    <div class="cp-tab active">Mods</div>
    <div class="cp-tab">Backups</div>
    <div class="cp-tab">Configuration</div>
  </div>

  <!-- Installed mods -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Installed Mods (5)</span>
      <span class="cp-muted" style="font-weight: normal; font-size: 12px;">Changes apply on next server restart</span>
    </div>
    <div style="display: grid; grid-template-columns: 40px 2fr 1fr 1fr 120px; padding: 7px 14px; font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.4px; color: var(--md-default-fg-color--light); border-bottom: 1px solid var(--md-default-fg-color--lightest);">
      <span></span><span>Mod</span><span>Pin Strategy</span><span>Version</span><span></span>
    </div>

    <div class="cp-mod-row">
      <div class="cp-mod-icon">⚡</div>
      <div>
        <div style="font-weight: bold;">Lithium</div>
        <div style="font-size: 11px;" class="cp-muted">General-purpose server optimisation</div>
      </div>
      <span class="cp-badge cp-badge-pinned">PINNED</span>
      <div>
        <div style="font-size: 12px;">mc1.21-0.13.0</div>
        <div style="font-size: 11px;" class="cp-muted">installed: mc1.21-0.13.0</div>
      </div>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">Edit</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-mod-row">
      <div class="cp-mod-icon">🔧</div>
      <div>
        <div style="font-weight: bold;">Spark</div>
        <div style="font-size: 11px;" class="cp-muted">Performance profiler</div>
      </div>
      <span class="cp-badge cp-badge-latest">LATEST</span>
      <div>
        <div style="font-size: 12px;" class="cp-muted">latest</div>
        <div style="font-size: 11px;" class="cp-muted">installed: 1.10.119</div>
      </div>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">Edit</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-mod-row">
      <div class="cp-mod-icon">🌍</div>
      <div>
        <div style="font-weight: bold;">Chunky</div>
        <div style="font-size: 11px;" class="cp-muted">Pre-generates chunks</div>
      </div>
      <span class="cp-badge cp-badge-pinned">PINNED</span>
      <div>
        <div style="font-size: 12px;">1.4.28</div>
        <div style="font-size: 11px; color: #E67E22;">⬆ update available: 1.4.30</div>
      </div>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px; color: #856404; border-color: #E67E22;">Update</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-mod-row">
      <div class="cp-mod-icon">💬</div>
      <div>
        <div style="font-weight: bold;">EssentialsX</div>
        <div style="font-size: 11px;" class="cp-muted">Essential server commands</div>
      </div>
      <span class="cp-badge cp-badge-pinned">PINNED</span>
      <div>
        <div style="font-size: 12px;">2.21.0</div>
        <div style="font-size: 11px;" class="cp-muted">installed: 2.21.0</div>
      </div>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">Edit</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-mod-row">
      <div class="cp-mod-icon">🗺</div>
      <div>
        <div style="font-weight: bold;">dynmap</div>
        <div style="font-size: 11px;" class="cp-muted">Real-time web map</div>
      </div>
      <span class="cp-badge cp-badge-latest">LATEST</span>
      <div>
        <div style="font-size: 12px;" class="cp-muted">latest</div>
        <div style="font-size: 11px;" class="cp-muted">installed: 3.7.2</div>
      </div>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">Edit</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>
  </div>

  <!-- Add mod -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Add Mod</span>
    </div>
    <div style="padding: 12px 14px;">
      <input class="cp-input" placeholder="🔍  Search Modrinth for mods compatible with Paper 1.21.4..." style="margin-bottom: 12px;" />
      <div style="border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden;">
        <div class="cp-search-result" style="background: var(--md-default-fg-color--lightest);">
          <div class="cp-mod-icon">⚡</div>
          <div><div style="font-weight:bold;">Spark</div><div style="font-size:11px;" class="cp-muted">Performance profiler · 4.2M downloads</div></div>
          <span style="font-size:12px;" class="cp-muted">latest: 1.10.119</span>
          <button class="cp-btn" style="opacity:0.4;" disabled>Added</button>
        </div>
        <div class="cp-search-result">
          <div class="cp-mod-icon">🐸</div>
          <div><div style="font-weight:bold;">Chunky Pregenerator</div><div style="font-size:11px;" class="cp-muted">World pre-generation · 2.1M downloads</div></div>
          <span style="font-size:12px;" class="cp-muted">latest: 1.4.30</span>
          <button class="cp-btn" style="opacity:0.4;" disabled>Added</button>
        </div>
        <div class="cp-search-result">
          <div class="cp-mod-icon">🗂</div>
          <div><div style="font-weight:bold;">CoreProtect</div><div style="font-size:11px;" class="cp-muted">Block logging and rollback · 3.8M downloads</div></div>
          <span style="font-size:12px;" class="cp-muted">latest: 22.4</span>
          <button class="cp-btn cp-btn-primary" style="padding:4px 10px; font-size:12px;">+ Add</button>
        </div>
        <div class="cp-search-result">
          <div class="cp-mod-icon">🛡</div>
          <div><div style="font-weight:bold;">LuckPerms</div><div style="font-size:11px;" class="cp-muted">Permissions manager · 11M downloads</div></div>
          <span style="font-size:12px;" class="cp-muted">latest: 5.4.137</span>
          <button class="cp-btn cp-btn-primary" style="padding:4px 10px; font-size:12px;">+ Add</button>
        </div>
      </div>
    </div>
  </div>

</div>
