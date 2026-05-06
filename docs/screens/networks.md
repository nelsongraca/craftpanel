# Networks

Network list and network detail views.

<style>
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; font-size: 13px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; padding: 7px 14px; font-size: 13px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-badge-unhealthy { background: #fdecea; color: #721c24; }
.cp-badge-stopped { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-type { background: var(--md-primary-fg-color--transparent, #E8F5EE); color: var(--md-primary-fg-color); }
.cp-badge-proxy { background: #e8f0fe; color: #1a56db; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-network-row { display: grid; grid-template-columns: 2fr 1fr 1fr 100px; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 12px; font-size: 13px; }
.cp-network-row:last-child { border-bottom: none; }
.cp-server-row { display: grid; grid-template-columns: 30px 2fr 1fr 1fr 1fr; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 12px; font-size: 13px; }
.cp-server-row:last-child { border-bottom: none; }
.cp-kv { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--md-default-fg-color--lightest); font-size: 13px; }
.cp-kv:last-child { border-bottom: none; }
.cp-kv-key { color: var(--md-default-fg-color--light); }
.cp-grid-2 { display: grid; grid-template-columns: 2fr 1fr; gap: 16px; margin-bottom: 16px; }
.cp-card { background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; padding: 14px; }
.cp-card-title { font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.5px; color: var(--md-default-fg-color--light); margin-bottom: 8px; }
.cp-proxy-chip { display: inline-flex; align-items: center; gap: 5px; background: #e8f0fe; border-radius: 4px; padding: 3px 8px; font-size: 12px; color: #1a56db; }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- ── NETWORK LIST ───────────────────── -->
  <div style="font-size: 16px; font-weight: bold; margin-bottom: 4px;">Network List</div>
  <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Logical groupings of proxy and backend servers</div>

  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
    <div>
      <div style="font-size: 20px; font-weight: bold;">Networks</div>
      <div style="font-size: 12px;" class="cp-muted">3 networks · 9 servers total</div>
    </div>
    <button class="cp-btn cp-btn-primary">+ New Network</button>
  </div>

  <div class="cp-panel">
    <div style="display: grid; grid-template-columns: 2fr 1fr 1fr 100px; padding: 7px 14px; font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.4px; color: var(--md-default-fg-color--light); border-bottom: 1px solid var(--md-default-fg-color--lightest);">
      <span>Network</span><span>Servers</span><span>Players Online</span><span></span>
    </div>

    <div class="cp-network-row">
      <div>
        <div style="font-weight: bold;">Survival Network</div>
        <div style="font-size: 11px;" class="cp-muted">Main survival experience · mc.example.com</div>
      </div>
      <div style="font-size: 12px;">
        <div>1 proxy</div>
        <div class="cp-muted">3 backends</div>
      </div>
      <span style="font-weight: bold;">35 players</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <div class="cp-network-row">
      <div>
        <div style="font-weight: bold;">Creative Network</div>
        <div style="font-size: 11px;" class="cp-muted">Creative and build servers</div>
      </div>
      <div style="font-size: 12px;">
        <div>1 proxy</div>
        <div class="cp-muted">2 backends</div>
      </div>
      <span style="font-weight: bold;">8 players</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <div class="cp-network-row">
      <div>
        <div style="font-weight: bold;">Minigames Network</div>
        <div style="font-size: 11px;" class="cp-muted">Seasonal minigame servers — currently offline</div>
      </div>
      <div style="font-size: 12px;">
        <div>1 proxy</div>
        <div class="cp-muted">1 backend</div>
      </div>
      <span class="cp-muted">0 players</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>
  </div>

  <!-- ── NETWORK DETAIL ────────────────── -->
  <div style="font-size: 16px; font-weight: bold; margin: 32px 0 4px;">Network Detail — Survival Network</div>
  <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Opened by clicking View on a network row</div>

  <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
    <div>
      <div style="font-size: 22px; font-weight: bold;">Survival Network</div>
      <div style="font-size: 12px; margin-top: 3px;" class="cp-muted">Main survival experience · 35 players online</div>
    </div>
    <div style="display:flex;gap:8px;">
      <button class="cp-btn">✏️ Edit</button>
      <button class="cp-btn">🗑 Delete</button>
    </div>
  </div>

  <div class="cp-grid-2">

    <!-- Server list -->
    <div class="cp-panel">
      <div class="cp-panel-header">
        <span>Servers (4)</span>
        <button class="cp-btn" style="font-size:12px;">+ Add Server</button>
      </div>
      <div style="display:grid;grid-template-columns:30px 2fr 1fr 1fr 1fr;padding:7px 14px;font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;color:var(--md-default-fg-color--light);border-bottom:1px solid var(--md-default-fg-color--lightest);">
        <span></span><span>Server</span><span>Type</span><span>Status</span><span>Players</span>
      </div>

      <div class="cp-server-row">
        <span style="font-size:16px;">🔀</span>
        <div>
          <div style="font-weight:bold;">Proxy</div>
          <div style="font-size:11px;" class="cp-muted">mc.example.com</div>
        </div>
        <span class="cp-badge cp-badge-proxy">VELOCITY</span>
        <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
        <span>35</span>
      </div>
      <div class="cp-server-row">
        <span style="font-size:16px;">🎮</span>
        <div>
          <div style="font-weight:bold;">Survival</div>
          <div style="font-size:11px;" class="cp-muted">survival.mc.example.com</div>
        </div>
        <span class="cp-badge cp-badge-type">PAPER</span>
        <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
        <span>24</span>
      </div>
      <div class="cp-server-row">
        <span style="font-size:16px;">🎮</span>
        <div>
          <div style="font-weight:bold;">Survival 2</div>
          <div style="font-size:11px;" class="cp-muted">survival2.mc.example.com</div>
        </div>
        <span class="cp-badge cp-badge-type">PAPER</span>
        <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
        <span>11</span>
      </div>
      <div class="cp-server-row">
        <span style="font-size:16px;">🎮</span>
        <div>
          <div style="font-weight:bold;">Skyblock</div>
          <div style="font-size:11px;" class="cp-muted">Not exposed</div>
        </div>
        <span class="cp-badge cp-badge-type">FABRIC</span>
        <span class="cp-badge cp-badge-unhealthy">● UNHEALTHY</span>
        <span class="cp-muted">—</span>
      </div>
    </div>

    <!-- Proxy backend config -->
    <div>
      <div class="cp-panel">
        <div class="cp-panel-header">
          <span>Proxy Backend Config</span>
          <span class="cp-muted" style="font-weight:normal;font-size:12px;">Velocity · MANAGED</span>
        </div>
        <div style="padding: 12px 14px; font-size: 12px;" class="cp-muted">
          Backend order determines the default server players connect to and the order shown in the server list command.
        </div>
        <div style="padding: 0 14px 12px; display: flex; flex-direction: column; gap: 8px;">
          <div style="display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--md-default-fg-color--lightest);border-radius:4px;">
            <span class="cp-muted" style="font-size:16px;cursor:grab;">⠿</span>
            <span style="font-weight:bold;flex:1;">survival</span>
            <span class="cp-muted" style="font-size:12px;">→ Survival</span>
            <button class="cp-btn" style="padding:3px 7px;">✏️</button>
            <button class="cp-btn" style="padding:3px 7px;">🗑</button>
          </div>
          <div style="display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--md-default-fg-color--lightest);border-radius:4px;">
            <span class="cp-muted" style="font-size:16px;cursor:grab;">⠿</span>
            <span style="font-weight:bold;flex:1;">survival2</span>
            <span class="cp-muted" style="font-size:12px;">→ Survival 2</span>
            <button class="cp-btn" style="padding:3px 7px;">✏️</button>
            <button class="cp-btn" style="padding:3px 7px;">🗑</button>
          </div>
          <div style="display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--md-default-fg-color--lightest);border-radius:4px;">
            <span class="cp-muted" style="font-size:16px;cursor:grab;">⠿</span>
            <span style="font-weight:bold;flex:1;">skyblock</span>
            <span class="cp-muted" style="font-size:12px;">→ Skyblock</span>
            <button class="cp-btn" style="padding:3px 7px;">✏️</button>
            <button class="cp-btn" style="padding:3px 7px;">🗑</button>
          </div>
          <button class="cp-btn" style="align-self:flex-start;">+ Add Backend</button>
        </div>
        <div style="padding: 8px 14px; border-top: 1px solid var(--md-default-fg-color--lightest); display:flex;justify-content:flex-end;">
          <button class="cp-btn cp-btn-primary" style="padding:5px 12px;">Save Order</button>
        </div>
      </div>
    </div>

  </div>

</div>
