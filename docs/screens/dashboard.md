# Dashboard

Home screen shown after login. Gives an at-a-glance overview of the entire platform.

<style>
.cp-card { background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; padding: 16px; }
.cp-card-title { font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.6px; color: var(--md-default-fg-color--light); margin-bottom: 8px; }
.cp-stat { font-size: 32px; font-weight: bold; color: var(--md-primary-fg-color); }
.cp-stat-sub { font-size: 12px; color: var(--md-default-fg-color--light); margin-top: 2px; }
.cp-grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 20px; }
.cp-grid-2 { display: grid; grid-template-columns: 2fr 1fr; gap: 16px; margin-bottom: 20px; }
.cp-section-title { font-size: 14px; font-weight: bold; margin-bottom: 12px; color: var(--md-default-fg-color); }
.cp-node-card { background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; padding: 12px; }
.cp-node-name { font-weight: bold; font-size: 13px; margin-bottom: 8px; }
.cp-metric-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; font-size: 12px; }
.cp-metric-label { color: var(--md-default-fg-color--light); width: 36px; }
.cp-bar-wrap { flex: 1; margin: 0 8px; background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 5px; }
.cp-bar { height: 5px; border-radius: 4px; background: var(--md-primary-fg-color); }
.cp-bar-warn { background: #E67E22; }
.cp-bar-danger { background: #E74C3C; }
.cp-activity-item { display: flex; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--md-default-fg-color--lightest); font-size: 13px; align-items: flex-start; }
.cp-activity-item:last-child { border-bottom: none; }
.cp-activity-time { font-size: 11px; color: var(--md-default-fg-color--light); white-space: nowrap; margin-top: 1px; }
.cp-activity-icon { width: 20px; text-align: center; }
.cp-alert-item { display: flex; gap: 10px; padding: 8px 12px; border-radius: 4px; background: #fff3e0; border-left: 3px solid #E67E22; margin-bottom: 8px; font-size: 13px; align-items: center; }
.cp-alert-item.danger { background: #fdecea; border-left-color: #E74C3C; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-badge-unhealthy { background: #fdecea; color: #721c24; }
.cp-badge-stopped { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-pending { background: #fff3e0; color: #856404; }
.cp-attention-item { display: grid; grid-template-columns: 1fr auto auto; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--md-default-fg-color--lightest); font-size: 13px; align-items: center; }
.cp-attention-item:last-child { border-bottom: none; }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 960px;">

  <!-- Summary cards -->
  <div class="cp-grid-4">
    <div class="cp-card">
      <div class="cp-card-title">Servers</div>
      <div class="cp-stat">6</div>
      <div class="cp-stat-sub">5 running · 1 stopped</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">Players Online</div>
      <div class="cp-stat">70</div>
      <div class="cp-stat-sub">across 4 servers</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">Nodes</div>
      <div class="cp-stat">2</div>
      <div class="cp-stat-sub">2 active · 0 degraded</div>
    </div>
    <div class="cp-card">
      <div class="cp-card-title">Active Alerts</div>
      <div class="cp-stat" style="color: #E67E22;">2</div>
      <div class="cp-stat-sub">1 critical · 1 warning</div>
    </div>
  </div>

  <!-- Active alerts -->
  <div style="margin-bottom: 20px;">
    <div class="cp-section-title">🔔 Active Alerts</div>
    <div class="cp-alert-item danger">
      <span>●</span>
      <span><strong>Skyblock</strong> — server UNHEALTHY for 14 minutes</span>
      <span style="margin-left: auto; font-size: 11px; color: #5D6D7E;">10:46</span>
    </div>
    <div class="cp-alert-item">
      <span>●</span>
      <span><strong>node-1</strong> — RAM at 91% (threshold: 90%)</span>
      <span style="margin-left: auto; font-size: 11px; color: #5D6D7E;">10:51</span>
    </div>
  </div>

  <!-- Node health + activity -->
  <div class="cp-grid-2">

    <!-- Node health -->
    <div>
      <div class="cp-section-title">🖥 Node Health</div>
      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">

        <div class="cp-node-card">
          <div class="cp-node-name">node-1 <span class="cp-badge cp-badge-healthy" style="font-weight: normal;">ACTIVE</span></div>
          <div class="cp-metric-row">
            <span class="cp-metric-label">CPU</span>
            <div class="cp-bar-wrap"><div class="cp-bar" style="width: 44%;"></div></div>
            <span>44%</span>
          </div>
          <div class="cp-metric-row">
            <span class="cp-metric-label">RAM</span>
            <div class="cp-bar-wrap"><div class="cp-bar cp-bar-danger" style="width: 91%;"></div></div>
            <span>91%</span>
          </div>
          <div class="cp-metric-row">
            <span class="cp-metric-label">Disk</span>
            <div class="cp-bar-wrap"><div class="cp-bar" style="width: 58%;"></div></div>
            <span>58%</span>
          </div>
          <div style="font-size: 11px; color: var(--md-default-fg-color--light); margin-top: 6px;">4 servers · 29.8 / 32 GB RAM</div>
        </div>

        <div class="cp-node-card">
          <div class="cp-node-name">node-2 <span class="cp-badge cp-badge-healthy" style="font-weight: normal;">ACTIVE</span></div>
          <div class="cp-metric-row">
            <span class="cp-metric-label">CPU</span>
            <div class="cp-bar-wrap"><div class="cp-bar" style="width: 22%;"></div></div>
            <span>22%</span>
          </div>
          <div class="cp-metric-row">
            <span class="cp-metric-label">RAM</span>
            <div class="cp-bar-wrap"><div class="cp-bar" style="width: 37%;"></div></div>
            <span>37%</span>
          </div>
          <div class="cp-metric-row">
            <span class="cp-metric-label">Disk</span>
            <div class="cp-bar-wrap"><div class="cp-bar" style="width: 31%;"></div></div>
            <span>31%</span>
          </div>
          <div style="font-size: 11px; color: var(--md-default-fg-color--light); margin-top: 6px;">2 servers · 12.1 / 32 GB RAM</div>
        </div>

      </div>
    </div>

    <!-- Recent activity -->
    <div>
      <div class="cp-section-title">📋 Recent Activity</div>
      <div class="cp-card" style="padding: 8px 12px;">
        <div class="cp-activity-item">
          <span class="cp-activity-icon">🔴</span>
          <span style="flex: 1;"><strong>Skyblock</strong> changed to UNHEALTHY</span>
          <span class="cp-activity-time">10:46</span>
        </div>
        <div class="cp-activity-item">
          <span class="cp-activity-icon">✅</span>
          <span style="flex: 1;"><strong>Survival</strong> backup completed (512 MB)</span>
          <span class="cp-activity-time">10:30</span>
        </div>
        <div class="cp-activity-item">
          <span class="cp-activity-icon">⟳</span>
          <span style="flex: 1;"><strong>Survival 2</strong> migration to node-2 started</span>
          <span class="cp-activity-time">10:00</span>
        </div>
        <div class="cp-activity-item">
          <span class="cp-activity-icon">▶</span>
          <span style="flex: 1;"><strong>Minigames</strong> started by admin</span>
          <span class="cp-activity-time">09:45</span>
        </div>
        <div class="cp-activity-item">
          <span class="cp-activity-icon">🖥</span>
          <span style="flex: 1;">New node <strong>node-3</strong> pending trust</span>
          <span class="cp-activity-time">09:12</span>
        </div>
        <div class="cp-activity-item">
          <span class="cp-activity-icon">■</span>
          <span style="flex: 1;"><strong>Creative</strong> restarted by jsmith</span>
          <span class="cp-activity-time">08:55</span>
        </div>
      </div>
    </div>

  </div>

  <!-- Servers needing attention -->
  <div>
    <div class="cp-section-title">⚠️ Needs Attention</div>
    <div class="cp-card" style="padding: 8px 16px;">
      <div class="cp-attention-item">
        <div>
          <div style="font-weight: bold;">Skyblock</div>
          <div style="font-size: 11px; color: var(--md-default-fg-color--light);">UNHEALTHY for 14 min — container running, health check failing</div>
        </div>
        <span class="cp-badge cp-badge-unhealthy">UNHEALTHY</span>
        <div style="display: flex; gap: 6px;">
          <button style="background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 4px 10px; font-size: 12px; cursor: pointer; color: var(--md-default-fg-color);">↺ Restart</button>
          <button style="background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 4px 10px; font-size: 12px; cursor: pointer; color: var(--md-default-fg-color);">View</button>
        </div>
      </div>
      <div class="cp-attention-item">
        <div>
          <div style="font-weight: bold;">node-3</div>
          <div style="font-size: 11px; color: var(--md-default-fg-color--light);">New node registered — waiting for admin trust</div>
        </div>
        <span class="cp-badge cp-badge-pending">PENDING</span>
        <div style="display: flex; gap: 6px;">
          <button style="background: var(--md-primary-fg-color); color: white; border: none; border-radius: 4px; padding: 4px 10px; font-size: 12px; cursor: pointer;">Trust Node</button>
          <button style="background: var(--md-default-bg-color); border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 4px 10px; font-size: 12px; cursor: pointer; color: var(--md-default-fg-color);">View</button>
        </div>
      </div>
    </div>
  </div>

</div>
