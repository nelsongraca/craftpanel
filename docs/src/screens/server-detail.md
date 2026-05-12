# Server Detail

Overview tab shown when opening a server. Tabs across the top switch between Overview, Console, Files, Mods, Backups, and Configuration.

<style>
.cp-tabs { display: flex; border-bottom: 2px solid var(--md-default-fg-color--lightest); margin-bottom: 20px; gap: 0; }
.cp-tab { padding: 8px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color--light); border-bottom: 2px solid transparent; margin-bottom: -2px; }
.cp-tab.active { color: var(--md-primary-fg-color); border-bottom-color: var(--md-primary-fg-color); font-weight: bold; }
.cp-grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 20px; }
.cp-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
.cp-card { background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; padding: 16px; }
.cp-card-title { font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.6px; color: var(--md-default-fg-color--light); margin-bottom: 10px; }
.cp-stat { font-size: 28px; font-weight: bold; color: var(--md-primary-fg-color); }
.cp-stat-sub { font-size: 12px; color: var(--md-default-fg-color--light); margin-top: 2px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-badge-type { background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); }
.cp-badge-managed { background: #e8f0fe; color: #1a56db; }
.cp-metric-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13px; }
.cp-metric-label { color: var(--md-default-fg-color--light); width: 40px; font-size: 12px; }
.cp-bar-wrap { flex: 1; margin: 0 10px; background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 6px; }
.cp-bar { height: 6px; border-radius: 4px; background: var(--md-primary-fg-color); }
.cp-kv { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--md-default-fg-color--lightest); font-size: 13px; }
.cp-kv:last-child { border-bottom: none; }
.cp-kv-key { color: var(--md-default-fg-color--light); }
.cp-player-list { display: flex; flex-wrap: wrap; gap: 6px; }
.cp-player { background: var(--md-default-fg-color--lightest); border-radius: 4px; padding: 3px 8px; font-size: 12px; }
.cp-btn { border-radius: 4px; padding: 6px 12px; font-size: 13px; cursor: pointer; }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; }
.cp-btn-secondary { background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-danger { background: #fdecea; color: #721c24; border: 1px solid #f5c6cb; }
.cp-muted { color: var(--md-default-fg-color--light); }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- Page header -->
  <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
    <div>
      <div style="font-size: 12px; margin-bottom: 4px;" class="cp-muted">Servers / Survival Network /</div>
      <div style="display: flex; align-items: center; gap: 10px;">
        <span style="font-size: 22px; font-weight: bold;">Survival</span>
        <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
        <span class="cp-badge cp-badge-type">PAPER 1.21.4</span>
        <span class="cp-badge cp-badge-managed">MANAGED</span>
      </div>
      <div style="font-size: 12px; margin-top: 4px;" class="cp-muted">survival.mc.example.com · node-1</div>
    </div>
    <div style="display: flex; gap: 8px;">
      <button class="cp-btn cp-btn-secondary">↺ Restart</button>
      <button class="cp-btn cp-btn-secondary">■ Stop</button>
      <button class="cp-btn cp-btn-secondary">···</button>
    </div>
  </div>

  <!-- Tabs -->
  <div class="cp-tabs">
    <div class="cp-tab active">Overview</div>
    <div class="cp-tab">Console</div>
    <div class="cp-tab">Files</div>
    <div class="cp-tab">Mods</div>
    <div class="cp-tab">Backups</div>
    <div class="cp-tab">Configuration</div>
  </div>

  <!-- Stats row -->
  <div class="cp-grid-3">
    <div class="cp-card">
      <div class="cp-card-title">Players Online</div>
      <div class="cp-stat">24</div>
      <div class="cp-stat-sub">of 50 max</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">RAM Usage</div>
      <div class="cp-stat">3.2 GB</div>
      <div class="cp-stat-sub">of 4 GB allocated</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">CPU Usage</div>
      <div class="cp-stat">38%</div>
      <div class="cp-stat-sub">512 CPU shares</div>
    </div>
  </div>

  <!-- Metrics + Info -->
  <div class="cp-grid-2">

    <!-- Live metrics -->
    <div class="cp-card">
      <div class="cp-card-title">Live Metrics</div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">CPU</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:38%;"></div></div>
        <span style="font-size:12px; width:36px; text-align:right;">38%</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">RAM</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:80%;"></div></div>
        <span style="font-size:12px; width:36px; text-align:right;">80%</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">Net ↓</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:20%;"></div></div>
        <span style="font-size:12px; width:56px; text-align:right;">200 KB/s</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">Net ↑</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:12%;"></div></div>
        <span style="font-size:12px; width:56px; text-align:right;">100 KB/s</span>
      </div>
      <div style="margin-top: 10px; text-align: right;">
        <a style="font-size: 12px; color: var(--md-primary-fg-color); cursor: pointer;">View history →</a>
      </div>
    </div>

    <!-- Server info -->
    <div class="cp-card">
      <div class="cp-card-title">Server Info</div>
      <div class="cp-kv"><span class="cp-kv-key">Type</span><span>Paper 1.21.4</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Node</span><span>node-1</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Network</span><span>Survival Network</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Host port</span><span>25570</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Hostname</span><span>survival.mc.example.com</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Image tag</span><span>latest</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Last seen</span><span>just now</span></div>
    </div>

  </div>

  <!-- Players online -->
  <div class="cp-card" style="margin-bottom: 16px;">
    <div class="cp-card-title">Players Online (24)</div>
    <div class="cp-player-list">
      <span class="cp-player">Notch</span>
      <span class="cp-player">jeb_</span>
      <span class="cp-player">Dinnerbone</span>
      <span class="cp-player">Dream</span>
      <span class="cp-player">TechnoBlade</span>
      <span class="cp-player">xisumavoid</span>
      <span class="cp-player">Grian</span>
      <span class="cp-player">Mumbo</span>
      <span class="cp-player">ImpulseSV</span>
      <span class="cp-player">cubfan135</span>
      <span class="cp-player">Iskall85</span>
      <span class="cp-player">EthosLab</span>
      <span class="cp-player">+ 12 more</span>
    </div>
  </div>

  <!-- Danger zone -->
  <div class="cp-card" style="border-color: #f5c6cb;">
    <div class="cp-card-title" style="color: #721c24;">Danger Zone</div>
    <div style="display: flex; justify-content: space-between; align-items: center;">
      <div>
        <div style="font-size: 13px; font-weight: bold;">Delete Server</div>
        <div style="font-size: 12px;" class="cp-muted">Permanently removes the container and all server data. Server must be stopped first.</div>
      </div>
      <button class="cp-btn cp-btn-danger">Delete Server</button>
    </div>
  </div>

</div>
