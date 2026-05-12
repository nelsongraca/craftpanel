# Migration

Migration flow initiated from the server detail page (··· menu → Migrate).

<style>
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 10px 14px; font-size: 13px; font-weight: bold; display: flex; justify-content: space-between; align-items: center; }
.cp-btn { border-radius: 4px; padding: 6px 12px; font-size: 13px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-select { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 7px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); width: 100%; }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 7px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); width: 100%; box-sizing: border-box; }
.cp-step { display: flex; gap: 14px; align-items: flex-start; padding: 10px 0; border-bottom: 1px solid var(--md-default-fg-color--lightest); }
.cp-step:last-child { border-bottom: none; }
.cp-step-num { width: 26px; height: 26px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: bold; flex-shrink: 0; margin-top: 1px; }
.cp-step-done { background: #d4edda; color: #155724; }
.cp-step-active { background: var(--md-primary-fg-color); color: white; }
.cp-step-pending { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-step-failed { background: #fdecea; color: #721c24; }
.cp-step-title { font-size: 13px; font-weight: bold; margin-bottom: 2px; }
.cp-step-detail { font-size: 12px; }
.cp-progress-bar-wrap { background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 8px; margin: 6px 0; }
.cp-progress-bar { height: 8px; border-radius: 4px; background: var(--md-primary-fg-color); }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-syncing { background: #e8f0fe; color: #1a56db; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-node-option { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; padding: 12px 14px; cursor: pointer; margin-bottom: 8px; }
.cp-node-option.selected { border-color: var(--md-primary-fg-color); background: var(--md-primary-fg-color--transparent, #E8F5EE); }
</style>

<div style="font-family: Arial, sans-serif; font-size: 14px; color: var(--md-default-fg-color); max-width: 860px;">

  <!-- === SCREEN 1: Initiate migration === -->
  <div style="margin-bottom: 40px;">
    <div style="font-size: 16px; font-weight: bold; margin-bottom: 4px;">Step 1 — Initiate Migration</div>
    <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Modal shown when selecting Migrate from the server actions menu</div>

    <div style="border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; background: var(--md-default-bg-color); max-width: 520px;">
      <div style="padding: 16px 20px; border-bottom: 1px solid var(--md-default-fg-color--lightest);">
        <div style="font-size: 16px; font-weight: bold;">Migrate Survival</div>
        <div style="font-size: 12px; margin-top: 3px;" class="cp-muted">Currently on <strong>node-1</strong> · Server will remain live during initial sync</div>
      </div>
      <div style="padding: 16px 20px;">
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 8px;" class="cp-muted">DESTINATION NODE</div>

        <div class="cp-node-option">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <div>
              <div style="font-weight: bold;">node-1 <span style="font-size:11px; color: var(--md-default-fg-color--light);">(current)</span></div>
              <div style="font-size: 11px;" class="cp-muted">RAM: 29.8 / 32 GB · CPU: 768 / 1024 shares</div>
            </div>
          </div>
        </div>

        <div class="cp-node-option selected">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <div>
              <div style="font-weight: bold;">node-2 ✓</div>
              <div style="font-size: 11px;" class="cp-muted">RAM: 12.1 / 32 GB · CPU: 256 / 1024 shares · Available: 19.9 GB</div>
            </div>
            <span class="cp-badge cp-badge-healthy">ACTIVE</span>
          </div>
        </div>

        <div style="margin-top: 14px;">
          <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px;" class="cp-muted">IN-GAME WARNING MESSAGE (OPTIONAL)</div>
          <input class="cp-input" value="Server restarting in 60 seconds, please find a safe spot" />
          <div style="font-size: 11px; margin-top: 4px;" class="cp-muted">Sent to players via stdin before the final sync pass</div>
        </div>
      </div>
      <div style="padding: 12px 20px; border-top: 1px solid var(--md-default-fg-color--lightest); display: flex; justify-content: flex-end; gap: 8px;">
        <button class="cp-btn">Cancel</button>
        <button class="cp-btn cp-btn-primary">Start Migration</button>
      </div>
    </div>
  </div>

  <!-- === SCREEN 2: Migration in progress === -->
  <div>
    <div style="font-size: 16px; font-weight: bold; margin-bottom: 4px;">Step 2 — Migration in Progress</div>
    <div style="font-size: 12px; margin-bottom: 16px;" class="cp-muted">Live view on the server detail page while migration runs</div>

    <!-- Migration status banner -->
    <div style="background: #e8f0fe; border: 1px solid #93b4f5; border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; display: flex; justify-content: space-between; align-items: center;">
      <div style="display: flex; gap: 10px; align-items: center;">
        <span style="font-size: 18px;">⟳</span>
        <div>
          <div style="font-weight: bold; font-size: 13px;">Migration in progress — node-1 → node-2</div>
          <div style="font-size: 12px; color: #1a56db;">Step 2 of 11 · Initial rsync · 62% complete · ~4 min remaining</div>
        </div>
      </div>
      <span class="cp-badge cp-badge-syncing">SYNCING</span>
    </div>

    <!-- Rsync progress -->
    <div class="cp-panel">
      <div class="cp-panel-header">Initial Rsync Progress</div>
      <div style="padding: 14px;">
        <div style="display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 6px;">
          <span class="cp-muted">sending incremental file list</span>
          <span style="font-weight: bold;">62% · 1.3 GB / 2.1 GB</span>
        </div>
        <div class="cp-progress-bar-wrap"><div class="cp-progress-bar" style="width: 62%;"></div></div>
        <div style="font-size: 11px; margin-top: 6px; display: flex; justify-content: space-between;" class="cp-muted">
          <span>Server remains live — players unaffected during this phase</span>
          <span>~4 min remaining</span>
        </div>
      </div>
    </div>

    <!-- Step log -->
    <div class="cp-panel">
      <div class="cp-panel-header">Migration Steps</div>
      <div style="padding: 12px 16px;">

        <div class="cp-step">
          <div class="cp-step-num cp-step-done">✓</div>
          <div>
            <div class="cp-step-title">Migration initiated</div>
            <div class="cp-step-detail cp-muted">node-1 → node-2 · started by admin · 10:00:00</div>
          </div>
        </div>

        <div class="cp-step">
          <div class="cp-step-num cp-step-active">2</div>
          <div style="flex: 1;">
            <div class="cp-step-title" style="color: var(--md-primary-fg-color);">Initial rsync <span style="font-weight:normal; color: var(--md-default-fg-color--light);">(in progress)</span></div>
            <div class="cp-step-detail cp-muted">Server remains running on node-1 · 10:00:05</div>
            <div class="cp-progress-bar-wrap" style="max-width: 300px;"><div class="cp-progress-bar" style="width: 62%;"></div></div>
            <div style="font-size: 11px;" class="cp-muted">1.3 GB / 2.1 GB · 62%</div>
          </div>
        </div>

        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">3</div>
          <div><div class="cp-step-title cp-muted">Initial rsync complete</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">4</div>
          <div><div class="cp-step-title cp-muted">In-game warning broadcast</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">5</div>
          <div><div class="cp-step-title cp-muted">save-all + save-off via stdin</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">6</div>
          <div><div class="cp-step-title cp-muted">Final delta rsync</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">7</div>
          <div><div class="cp-step-title cp-muted">New container started on node-2</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">8</div>
          <div><div class="cp-step-title cp-muted">DNS record updated</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">9</div>
          <div><div class="cp-step-title cp-muted">Ingress live on node-2</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">10</div>
          <div><div class="cp-step-title cp-muted">Old container removed from node-1</div></div>
        </div>
        <div class="cp-step">
          <div class="cp-step-num cp-step-pending">11</div>
          <div><div class="cp-step-title cp-muted">Server record updated — migration complete</div></div>
        </div>

      </div>
    </div>
  </div>

</div>
