# Server List

<style>
.cp-filters { display: flex; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); width: 200px; }
.cp-select { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color--light); }
.cp-table-header { background: var(--md-primary-fg-color--transparent, #E8F5EE); border: 1px solid var(--md-default-fg-color--lightest); border-bottom: none; padding: 8px 16px; display: grid; grid-template-columns: 2fr 1fr 1fr 1fr 1.2fr 0.8fr 110px; font-size: 11px; font-weight: bold; color: var(--md-primary-fg-color); text-transform: uppercase; letter-spacing: 0.5px; border-radius: 6px 6px 0 0; }
.cp-row { border: 1px solid var(--md-default-fg-color--lightest); border-top: none; padding: 11px 16px; display: grid; grid-template-columns: 2fr 1fr 1fr 1fr 1.2fr 0.8fr 110px; align-items: center; background: var(--md-default-bg-color); }
.cp-row:nth-child(odd) { background: var(--md-default-bg-color); filter: brightness(0.98); }
.cp-row:last-child { border-radius: 0 0 6px 6px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-badge-unhealthy { background: #fdecea; color: #721c24; }
.cp-badge-stopped { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-starting { background: #fff3e0; color: #856404; }
.cp-badge-type { background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); }
.cp-bar-wrap { background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px; }
.cp-bar { height: 4px; border-radius: 4px; background: var(--md-primary-fg-color); }
.cp-bar-warn { background: #E67E22; }
.cp-bar-danger { background: #E74C3C; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-btn { border-radius: 4px; padding: 4px 8px; font-size: 12px; cursor: pointer; }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; }
.cp-btn-secondary { background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- Page header -->
  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
    <div>
      <div style="font-size: 20px; font-weight: bold;">Servers</div>
      <div style="font-size: 12px;" class="cp-muted">6 servers across 2 nodes</div>
    </div>
    <button class="cp-btn cp-btn-primary" style="padding: 8px 16px; font-size: 13px;">+ New Server</button>
  </div>

  <!-- Filters -->
  <div class="cp-filters">
    <input class="cp-input" placeholder="🔍  Search servers..." />
    <select class="cp-select"><option>All statuses</option><option>Healthy</option><option>Stopped</option><option>Unhealthy</option></select>
    <select class="cp-select"><option>All networks</option><option>Survival Network</option></select>
    <select class="cp-select"><option>All nodes</option><option>node-1</option><option>node-2</option></select>
  </div>

  <!-- Table -->
  <div class="cp-table-header">
    <span>Server</span><span>Type</span><span>Status</span><span>Players</span><span>RAM</span><span>Node</span><span></span>
  </div>

  <!-- Healthy -->
  <div class="cp-row">
    <div><div style="font-weight: bold;">Survival</div><div style="font-size: 11px;" class="cp-muted">survival.mc.example.com</div></div>
    <span class="cp-badge cp-badge-type">PAPER 1.21.4</span>
    <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    <span>24 / 50</span>
    <div><div style="font-size: 12px;">3.2 / 4 GB</div><div class="cp-bar-wrap"><div class="cp-bar" style="width:80%;"></div></div></div>
    <span class="cp-muted" style="font-size:12px;">node-1</span>
    <div style="display:flex;gap:5px;">
      <button class="cp-btn cp-btn-secondary">■ Stop</button>
      <button class="cp-btn cp-btn-secondary">···</button>
    </div>
  </div>

  <!-- Healthy -->
  <div class="cp-row">
    <div><div style="font-weight: bold;">Creative</div><div style="font-size: 11px;" class="cp-muted">creative.mc.example.com</div></div>
    <span class="cp-badge cp-badge-type">PAPER 1.21.4</span>
    <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    <span>8 / 50</span>
    <div><div style="font-size: 12px;">1.8 / 4 GB</div><div class="cp-bar-wrap"><div class="cp-bar" style="width:45%;"></div></div></div>
    <span class="cp-muted" style="font-size:12px;">node-1</span>
    <div style="display:flex;gap:5px;">
      <button class="cp-btn cp-btn-secondary">■ Stop</button>
      <button class="cp-btn cp-btn-secondary">···</button>
    </div>
  </div>

  <!-- Unhealthy -->
  <div class="cp-row">
    <div><div style="font-weight: bold;">Skyblock</div><div style="font-size: 11px;" class="cp-muted">Not exposed</div></div>
    <span class="cp-badge cp-badge-type">FABRIC 1.21.4</span>
    <span class="cp-badge cp-badge-unhealthy">● UNHEALTHY</span>
    <span class="cp-muted">— / 20</span>
    <div><div style="font-size: 12px;">1.9 / 2 GB</div><div class="cp-bar-wrap"><div class="cp-bar cp-bar-danger" style="width:95%;"></div></div></div>
    <span class="cp-muted" style="font-size:12px;">node-2</span>
    <div style="display:flex;gap:5px;">
      <button class="cp-btn cp-btn-secondary">↺ Restart</button>
      <button class="cp-btn cp-btn-secondary">···</button>
    </div>
  </div>

  <!-- Stopped -->
  <div class="cp-row">
    <div><div style="font-weight: bold; " class="cp-muted">Minigames</div><div style="font-size: 11px;" class="cp-muted">Not exposed</div></div>
    <span class="cp-badge cp-badge-type">PAPER 1.21.4</span>
    <span class="cp-badge cp-badge-stopped">● STOPPED</span>
    <span class="cp-muted">— / 30</span>
    <div><div style="font-size: 12px;" class="cp-muted">— / 8 GB</div><div class="cp-bar-wrap"><div class="cp-bar" style="width:0%;"></div></div></div>
    <span class="cp-muted" style="font-size:12px;">node-2</span>
    <div style="display:flex;gap:5px;">
      <button class="cp-btn cp-btn-primary">▶ Start</button>
      <button class="cp-btn cp-btn-secondary">···</button>
    </div>
  </div>

  <!-- Migrating -->
  <div class="cp-row">
    <div>
      <div style="font-weight: bold;">Survival 2</div>
      <div style="font-size: 11px; color: #E67E22;">⟳ Migrating to node-2 — step 2/11 · 62%</div>
    </div>
    <span class="cp-badge cp-badge-type">PAPER 1.21.4</span>
    <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    <span>3 / 50</span>
    <div><div style="font-size: 12px;">2.1 / 4 GB</div><div class="cp-bar-wrap"><div class="cp-bar" style="width:52%;"></div></div></div>
    <span class="cp-muted" style="font-size:12px;">node-1</span>
    <div style="display:flex;gap:5px;">
      <button class="cp-btn cp-btn-secondary" style="opacity:0.4;" disabled>···</button>
    </div>
  </div>

  <!-- Proxy -->
  <div class="cp-row">
    <div><div style="font-weight: bold;">Proxy</div><div style="font-size: 11px;" class="cp-muted">mc.example.com</div></div>
    <span class="cp-badge cp-badge-type">VELOCITY</span>
    <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    <span>35 / 200</span>
    <div><div style="font-size: 12px;">0.4 / 1 GB</div><div class="cp-bar-wrap"><div class="cp-bar" style="width:40%;"></div></div></div>
    <span class="cp-muted" style="font-size:12px;">node-1</span>
    <div style="display:flex;gap:5px;">
      <button class="cp-btn cp-btn-secondary">■ Stop</button>
      <button class="cp-btn cp-btn-secondary">···</button>
    </div>
  </div>

</div>
