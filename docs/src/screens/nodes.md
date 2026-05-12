# Nodes

Node list and node detail views.

<style>
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; font-size: 13px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; padding: 7px 14px; font-size: 13px; }
.cp-btn-trust { background: #d4edda; color: #155724; border: 1px solid #b8dfc4; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-active { background: #d4edda; color: #155724; }
.cp-badge-degraded { background: #fdecea; color: #721c24; }
.cp-badge-pending { background: #fff3e0; color: #856404; }
.cp-badge-decommissioned { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-node-row { display: grid; grid-template-columns: 2fr 1fr 1.5fr 1.5fr 1fr 120px; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 12px; font-size: 13px; }
.cp-node-row:last-child { border-bottom: none; }
.cp-metric-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13px; }
.cp-metric-label { color: var(--md-default-fg-color--light); width: 44px; font-size: 12px; }
.cp-bar-wrap { flex: 1; margin: 0 10px; background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 6px; }
.cp-bar { height: 6px; border-radius: 4px; background: var(--md-primary-fg-color); }
.cp-bar-warn { background: #E67E22; }
.cp-bar-danger { background: #E74C3C; }
.cp-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.cp-grid-3 { display: grid; grid-template-columns: repeat(3,1fr); gap: 12px; margin-bottom: 16px; }
.cp-card { background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; padding: 14px; }
.cp-card-title { font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.5px; color: var(--md-default-fg-color--light); margin-bottom: 8px; }
.cp-stat { font-size: 26px; font-weight: bold; color: var(--md-primary-fg-color); }
.cp-kv { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--md-default-fg-color--lightest); font-size: 13px; }
.cp-kv:last-child { border-bottom: none; }
.cp-kv-key { color: var(--md-default-fg-color--light); }
.cp-server-row { display: grid; grid-template-columns: 2fr 1fr 1fr; padding: 8px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); font-size: 13px; align-items: center; gap: 10px; }
.cp-server-row:last-child { border-bottom: none; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-badge-stopped { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-unhealthy { background: #fdecea; color: #721c24; }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- ── NODE LIST ─────────────────────────────── -->
  <div style="font-size: 16px; font-weight: bold; margin-bottom: 4px;">Node List</div>
  <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">All nodes registered with this master</div>

  <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
    <div>
      <div style="font-size: 20px; font-weight: bold;">Nodes</div>
      <div style="font-size: 12px;" class="cp-muted">3 nodes · 2 active · 1 pending</div>
    </div>
  </div>

  <div class="cp-panel">
    <div style="display: grid; grid-template-columns: 2fr 1fr 1.5fr 1.5fr 1fr 120px; padding: 7px 14px; font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.4px; color: var(--md-default-fg-color--light); border-bottom: 1px solid var(--md-default-fg-color--lightest);">
      <span>Node</span><span>Status</span><span>RAM</span><span>CPU</span><span>Servers</span><span></span>
    </div>

    <!-- Active node -->
    <div class="cp-node-row">
      <div>
        <div style="font-weight: bold;">node-1</div>
        <div style="font-size: 11px;" class="cp-muted">10.0.0.1 · 1.2.3.4 · v1.0.0</div>
      </div>
      <span class="cp-badge cp-badge-active">● ACTIVE</span>
      <div>
        <div style="font-size: 12px;">29.8 / 32 GB</div>
        <div style="background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px;">
          <div class="cp-bar cp-bar-warn" style="width: 93%;"></div>
        </div>
      </div>
      <div>
        <div style="font-size: 12px;">768 / 1024 shares</div>
        <div style="background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px;">
          <div class="cp-bar" style="width: 75%;"></div>
        </div>
      </div>
      <span>4 servers</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <!-- Active node -->
    <div class="cp-node-row">
      <div>
        <div style="font-weight: bold;">node-2</div>
        <div style="font-size: 11px;" class="cp-muted">10.0.0.2 · 1.2.3.5 · v1.0.0</div>
      </div>
      <span class="cp-badge cp-badge-active">● ACTIVE</span>
      <div>
        <div style="font-size: 12px;">12.1 / 32 GB</div>
        <div style="background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px;">
          <div class="cp-bar" style="width: 38%;"></div>
        </div>
      </div>
      <div>
        <div style="font-size: 12px;">256 / 1024 shares</div>
        <div style="background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px;">
          <div class="cp-bar" style="width: 25%;"></div>
        </div>
      </div>
      <span>2 servers</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn">View</button>
        <button class="cp-btn">···</button>
      </div>
    </div>

    <!-- Pending node -->
    <div class="cp-node-row" style="background: #fffdf5;">
      <div>
        <div style="font-weight: bold;">node-3</div>
        <div style="font-size: 11px;" class="cp-muted">10.0.0.3 · 1.2.3.6 · v1.0.0 · registered 9 min ago</div>
      </div>
      <span class="cp-badge cp-badge-pending">● PENDING</span>
      <div>
        <div style="font-size: 12px;" class="cp-muted">0 / 64 GB</div>
        <div style="background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px;"></div>
      </div>
      <div>
        <div style="font-size: 12px;" class="cp-muted">0 / 2048 shares</div>
        <div style="background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 4px; margin-top: 4px;"></div>
      </div>
      <span class="cp-muted">—</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn cp-btn-trust">✓ Trust</button>
        <button class="cp-btn">···</button>
      </div>
    </div>
  </div>

  <!-- ── NODE DETAIL ────────────────────────────── -->
  <div style="font-size: 16px; font-weight: bold; margin: 32px 0 4px;">Node Detail — node-1</div>
  <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Opened by clicking View on a node row</div>

  <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
    <div>
      <div style="display: flex; align-items: center; gap: 10px;">
        <span style="font-size: 22px; font-weight: bold;">node-1</span>
        <span class="cp-badge cp-badge-active">● ACTIVE</span>
      </div>
      <div style="font-size: 12px; margin-top: 4px;" class="cp-muted">10.0.0.1 (private) · 1.2.3.4 (public) · agent v1.0.0</div>
    </div>
    <div style="display:flex;gap:8px;">
      <button class="cp-btn">⚙ Edit</button>
      <button class="cp-btn">⏻ Shutdown</button>
      <button class="cp-btn">🔑 Rotate Key</button>
    </div>
  </div>

  <!-- Stats -->
  <div class="cp-grid-3">
    <div class="cp-card">
      <div class="cp-card-title">RAM</div>
      <div class="cp-stat">93%</div>
      <div style="font-size:12px;" class="cp-muted">29.8 / 32 GB used</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">CPU</div>
      <div class="cp-stat">44%</div>
      <div style="font-size:12px;" class="cp-muted">aggregate across all cores</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">Disk</div>
      <div class="cp-stat">58%</div>
      <div style="font-size:12px;" class="cp-muted">580 / 1000 GB used</div>
    </div>
  </div>

  <!-- Metrics + Info -->
  <div class="cp-grid-2">
    <div class="cp-card">
      <div class="cp-card-title">Live Metrics</div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">CPU</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:44%;"></div></div>
        <span style="font-size:12px;width:36px;text-align:right;">44%</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">RAM</span>
        <div class="cp-bar-wrap"><div class="cp-bar cp-bar-warn" style="width:93%;"></div></div>
        <span style="font-size:12px;width:36px;text-align:right;">93%</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">Disk</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:58%;"></div></div>
        <span style="font-size:12px;width:36px;text-align:right;">58%</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">Net ↓</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:30%;"></div></div>
        <span style="font-size:12px;width:56px;text-align:right;">3 MB/s</span>
      </div>
      <div class="cp-metric-row">
        <span class="cp-metric-label">Net ↑</span>
        <div class="cp-bar-wrap"><div class="cp-bar" style="width:18%;"></div></div>
        <span style="font-size:12px;width:56px;text-align:right;">1.8 MB/s</span>
      </div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">Node Info</div>
      <div class="cp-kv"><span class="cp-kv-key">Hostname</span><span>node1.internal</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Public IP</span><span>1.2.3.4</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Private IP</span><span>10.0.0.1</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Data path</span><span style="font-family:monospace;font-size:12px;">/data/craftpanel</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Port range</span><span>25570 – 26070</span></div>
      <div class="cp-kv"><span class="cp-kv-key">Agent version</span><span>1.0.0</span></div>
    </div>
  </div>

  <!-- Servers on this node -->
  <div class="cp-panel">
    <div class="cp-panel-header">Servers on node-1 (4)</div>
    <div style="display:grid;grid-template-columns:2fr 1fr 1fr;padding:7px 14px;font-size:11px;font-weight:bold;text-transform:uppercase;letter-spacing:0.4px;color:var(--md-default-fg-color--light);border-bottom:1px solid var(--md-default-fg-color--lightest);">
      <span>Server</span><span>Type</span><span>Status</span>
    </div>
    <div class="cp-server-row">
      <span style="font-weight:bold;">Survival</span>
      <span class="cp-badge" style="background:var(--md-primary-fg-color--transparent,#E8F5EE);color:var(--md-primary-fg-color);">PAPER</span>
      <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    </div>
    <div class="cp-server-row">
      <span style="font-weight:bold;">Creative</span>
      <span class="cp-badge" style="background:var(--md-primary-fg-color--transparent,#E8F5EE);color:var(--md-primary-fg-color);">PAPER</span>
      <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    </div>
    <div class="cp-server-row">
      <span style="font-weight:bold;">Survival 2</span>
      <span class="cp-badge" style="background:var(--md-primary-fg-color--transparent,#E8F5EE);color:var(--md-primary-fg-color);">PAPER</span>
      <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    </div>
    <div class="cp-server-row">
      <span style="font-weight:bold;">Proxy</span>
      <span class="cp-badge" style="background:var(--md-primary-fg-color--transparent,#E8F5EE);color:var(--md-primary-fg-color);">VELOCITY</span>
      <span class="cp-badge cp-badge-healthy">● HEALTHY</span>
    </div>
  </div>

</div>
