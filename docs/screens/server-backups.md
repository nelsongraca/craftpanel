# Server Backups

Backups tab on the server detail page.

<style>
.cp-tabs { display: flex; border-bottom: 2px solid var(--md-default-fg-color--lightest); margin-bottom: 20px; }
.cp-tab { padding: 8px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color--light); border-bottom: 2px solid transparent; margin-bottom: -2px; }
.cp-tab.active { color: var(--md-primary-fg-color); border-bottom-color: var(--md-primary-fg-color); font-weight: bold; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-completed { background: #d4edda; color: #155724; }
.cp-badge-failed { background: #fdecea; color: #721c24; }
.cp-badge-progress { background: #fff3e0; color: #856404; }
.cp-badge-manual { background: var(--md-default-fg-color--lightest); color: var(--md-default-fg-color--light); }
.cp-badge-scheduled { background: #e8f0fe; color: #1a56db; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; }
.cp-panel { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-bottom: 16px; }
.cp-panel-header { background: var(--md-default-fg-color--lightest); padding: 8px 14px; display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: bold; }
.cp-backup-row { display: grid; grid-template-columns: 1fr 80px 100px 140px 130px; align-items: center; padding: 11px 14px; border-bottom: 1px solid var(--md-default-fg-color--lightest); gap: 10px; font-size: 13px; }
.cp-backup-row:last-child { border-bottom: none; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-input { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 4px; padding: 6px 10px; font-size: 13px; background: var(--md-default-bg-color); color: var(--md-default-fg-color); }
.cp-progress-bar-wrap { background: var(--md-default-fg-color--lightest); border-radius: 4px; height: 5px; margin-top: 4px; }
.cp-progress-bar { height: 5px; border-radius: 4px; background: #E67E22; }
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
    <div class="cp-tab active">Backups</div>
    <div class="cp-tab">Configuration</div>
  </div>

  <!-- Schedule config -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Backup Schedule</span>
      <button class="cp-btn cp-btn-primary" style="padding:5px 12px;">+ Backup Now</button>
    </div>
    <div style="padding: 14px; display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: end;">
      <div>
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px;" class="cp-muted">SCHEDULE (CRON)</div>
        <input class="cp-input" value="0 4 * * *" style="width: 100%; box-sizing: border-box;" />
        <div style="font-size: 11px; margin-top: 4px;" class="cp-muted">Every day at 04:00 · Next run in 17h 08m</div>
      </div>
      <div>
        <div style="font-size: 12px; font-weight: bold; margin-bottom: 6px;" class="cp-muted">RETENTION (MAX BACKUPS)</div>
        <input class="cp-input" value="10" style="width: 80px;" />
        <div style="font-size: 11px; margin-top: 4px;" class="cp-muted">Oldest backup deleted when limit is reached</div>
      </div>
    </div>
    <div style="padding: 0 14px 14px; display: flex; justify-content: flex-end; gap: 8px;">
      <button class="cp-btn">Discard</button>
      <button class="cp-btn cp-btn-primary">Save Schedule</button>
    </div>
  </div>

  <!-- Backup list -->
  <div class="cp-panel">
    <div class="cp-panel-header">
      <span>Backups (7 / 10)</span>
      <span class="cp-muted" style="font-weight: normal; font-size: 12px;">Total: 3.8 GB on node-1</span>
    </div>
    <div style="display: grid; grid-template-columns: 1fr 80px 100px 140px 130px; padding: 7px 14px; font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.4px; color: var(--md-default-fg-color--light); border-bottom: 1px solid var(--md-default-fg-color--lightest);">
      <span>Created</span><span>Trigger</span><span>Status</span><span>Size</span><span></span>
    </div>

    <!-- In progress -->
    <div class="cp-backup-row" style="background: var(--md-default-fg-color--lightest);">
      <div>
        <div style="font-weight: bold;">2026-05-04 10:55</div>
        <div style="font-size: 11px;">
          <div class="cp-progress-bar-wrap" style="width: 200px;"><div class="cp-progress-bar" style="width: 42%;"></div></div>
        </div>
        <div style="font-size: 11px; color: #E67E22;">In progress · 42%</div>
      </div>
      <span class="cp-badge cp-badge-manual">MANUAL</span>
      <span class="cp-badge cp-badge-progress">● IN PROGRESS</span>
      <span class="cp-muted">—</span>
      <button class="cp-btn" style="opacity:0.4;" disabled>···</button>
    </div>

    <!-- Completed entries -->
    <div class="cp-backup-row">
      <div>
        <div style="font-weight: bold;">2026-05-04 04:00</div>
        <div style="font-size: 11px;" class="cp-muted">Completed in 3m 12s</div>
      </div>
      <span class="cp-badge cp-badge-scheduled">SCHEDULED</span>
      <span class="cp-badge cp-badge-completed">● COMPLETED</span>
      <span>512 MB</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">⬇ Export</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-backup-row">
      <div>
        <div style="font-weight: bold;">2026-05-03 04:00</div>
        <div style="font-size: 11px;" class="cp-muted">Completed in 2m 58s</div>
      </div>
      <span class="cp-badge cp-badge-scheduled">SCHEDULED</span>
      <span class="cp-badge cp-badge-completed">● COMPLETED</span>
      <span>508 MB</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">⬇ Export</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-backup-row">
      <div>
        <div style="font-weight: bold;">2026-05-02 14:30</div>
        <div style="font-size: 11px;" class="cp-muted">Completed in 3m 01s</div>
      </div>
      <span class="cp-badge cp-badge-manual">MANUAL</span>
      <span class="cp-badge cp-badge-completed">● COMPLETED</span>
      <span>501 MB</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px;">⬇ Export</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

    <div class="cp-backup-row">
      <div>
        <div style="font-weight: bold;">2026-05-01 04:00</div>
        <div style="font-size: 11px; color: #721c24;">Disk full on node-1</div>
      </div>
      <span class="cp-badge cp-badge-scheduled">SCHEDULED</span>
      <span class="cp-badge cp-badge-failed">● FAILED</span>
      <span class="cp-muted">—</span>
      <div style="display:flex;gap:5px;">
        <button class="cp-btn" style="padding:3px 8px; opacity:0.4;" disabled>⬇ Export</button>
        <button class="cp-btn" style="padding:3px 8px;">🗑</button>
      </div>
    </div>

  </div>

</div>
