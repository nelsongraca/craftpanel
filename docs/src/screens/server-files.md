# Server Files

File explorer tab on the server detail page.

<style>
.cp-tabs { display: flex; border-bottom: 2px solid var(--md-default-fg-color--lightest); margin-bottom: 20px; }
.cp-tab { padding: 8px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color--light); border-bottom: 2px solid transparent; margin-bottom: -2px; }
.cp-tab.active { color: var(--md-primary-fg-color); border-bottom-color: var(--md-primary-fg-color); font-weight: bold; }
.cp-files-wrap { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; }
.cp-files-toolbar { background: var(--md-default-fg-color--lightest); padding: 8px 12px; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.cp-breadcrumb { font-size: 13px; font-family: monospace; display: flex; align-items: center; gap: 4px; }
.cp-breadcrumb-sep { color: var(--md-default-fg-color--light); }
.cp-breadcrumb-item { color: var(--md-primary-fg-color); cursor: pointer; }
.cp-breadcrumb-current { color: var(--md-default-fg-color); }
.cp-files-actions { display: flex; gap: 6px; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
.cp-btn-primary { background: var(--md-primary-fg-color); color: white; border: none; }
.cp-file-header { background: var(--md-default-bg-color); border-bottom: 1px solid var(--md-default-fg-color--lightest); padding: 7px 14px; display: grid; grid-template-columns: 30px 2fr 80px 140px 100px; font-size: 11px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.4px; color: var(--md-default-fg-color--light); }
.cp-file-row { padding: 8px 14px; display: grid; grid-template-columns: 30px 2fr 80px 140px 100px; align-items: center; font-size: 13px; border-bottom: 1px solid var(--md-default-fg-color--lightest); cursor: pointer; }
.cp-file-row:last-child { border-bottom: none; }
.cp-file-row:hover { background: var(--md-default-fg-color--lightest); }
.cp-file-row.selected { background: var(--md-primary-fg-color--transparent, #E8F5EE); }
.cp-file-name { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.cp-file-icon { font-size: 15px; }
.cp-muted { color: var(--md-default-fg-color--light); }
.cp-context-menu { position: relative; display: inline-block; }
.cp-editor-wrap { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; margin-top: 16px; }
.cp-editor-header { background: var(--md-default-fg-color--lightest); padding: 8px 14px; display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.cp-editor-body { background: #1a1a1a; color: #d4d4d4; font-family: "Courier New", monospace; font-size: 12px; padding: 14px; height: 200px; line-height: 1.7; }
.cp-editor-footer { padding: 8px 14px; display: flex; justify-content: flex-end; gap: 8px; border-top: 1px solid var(--md-default-fg-color--lightest); }
.cp-key { color: #9cdcfe; }
.cp-val { color: #ce9178; }
.cp-comment { color: #6A9955; }
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
    <div class="cp-tab active">Files</div>
    <div class="cp-tab">Mods</div>
    <div class="cp-tab">Backups</div>
    <div class="cp-tab">Configuration</div>
  </div>

  <!-- File browser -->
  <div class="cp-files-wrap">
    <div class="cp-files-toolbar">
      <div class="cp-breadcrumb">
        <span class="cp-breadcrumb-item">~</span>
        <span class="cp-breadcrumb-sep">/</span>
        <span class="cp-breadcrumb-item">plugins</span>
        <span class="cp-breadcrumb-sep">/</span>
        <span class="cp-breadcrumb-current">EssentialsX</span>
      </div>
      <div class="cp-files-actions">
        <button class="cp-btn">⬆ Upload</button>
        <button class="cp-btn">📁 New Folder</button>
        <button class="cp-btn">📄 New File</button>
      </div>
    </div>
    <div class="cp-file-header">
      <span></span><span>Name</span><span>Size</span><span>Modified</span><span></span>
    </div>
    <div class="cp-file-row">
      <span class="cp-file-icon">📁</span>
      <div class="cp-file-name">.. (parent)</div>
      <span class="cp-muted">—</span><span class="cp-muted">—</span><span></span>
    </div>
    <div class="cp-file-row">
      <span class="cp-file-icon">📁</span>
      <div class="cp-file-name">data</div>
      <span class="cp-muted">—</span>
      <span class="cp-muted" style="font-size:12px;">2026-05-04 09:00</span>
      <span></span>
    </div>
    <div class="cp-file-row">
      <span class="cp-file-icon">📁</span>
      <div class="cp-file-name">lang</div>
      <span class="cp-muted">—</span>
      <span class="cp-muted" style="font-size:12px;">2026-05-01 14:22</span>
      <span></span>
    </div>
    <div class="cp-file-row selected">
      <span class="cp-file-icon">📄</span>
      <div class="cp-file-name"><strong>config.yml</strong></div>
      <span style="font-size:12px;">48 KB</span>
      <span class="cp-muted" style="font-size:12px;">2026-05-04 10:12</span>
      <div style="display:flex;gap:4px;">
        <button class="cp-btn" style="padding:3px 7px;">✏️</button>
        <button class="cp-btn" style="padding:3px 7px;">⬇</button>
        <button class="cp-btn" style="padding:3px 7px;">···</button>
      </div>
    </div>
    <div class="cp-file-row">
      <span class="cp-file-icon">📄</span>
      <div class="cp-file-name">userdata.db</div>
      <span style="font-size:12px;">2.1 MB</span>
      <span class="cp-muted" style="font-size:12px;">2026-05-04 08:00</span>
      <div style="display:flex;gap:4px;">
        <button class="cp-btn" style="padding:3px 7px;">⬇</button>
        <button class="cp-btn" style="padding:3px 7px;">···</button>
      </div>
    </div>
    <div class="cp-file-row">
      <span class="cp-file-icon">📄</span>
      <div class="cp-file-name">worth.yml</div>
      <span style="font-size:12px;">12 KB</span>
      <span class="cp-muted" style="font-size:12px;">2026-04-28 16:44</span>
      <div style="display:flex;gap:4px;">
        <button class="cp-btn" style="padding:3px 7px;">✏️</button>
        <button class="cp-btn" style="padding:3px 7px;">⬇</button>
        <button class="cp-btn" style="padding:3px 7px;">···</button>
      </div>
    </div>
  </div>

  <!-- File editor (open) -->
  <div class="cp-editor-wrap">
    <div class="cp-editor-header">
      <span>✏️ Editing: <strong>plugins/EssentialsX/config.yml</strong></span>
      <span class="cp-muted" style="font-size:12px;">48 KB · UTF-8</span>
    </div>
    <div class="cp-editor-body">
      <div class="cp-comment"># EssentialsX Configuration</div>
      <div><span class="cp-key">ops-name-color</span>: <span class="cp-val">'&amp;c'</span></div>
      <div><span class="cp-key">nickname-prefix</span>: <span class="cp-val">'~'</span></div>
      <div><span class="cp-key">max-nick-length</span>: <span class="cp-val">15</span></div>
      <div><span class="cp-key">ignore-case</span>: <span class="cp-val">true</span></div>
      <div><span class="cp-key">teleport-delay</span>: <span class="cp-val">3</span></div>
      <div><span class="cp-key">currency-symbol</span>: <span class="cp-val">'$'</span></div>
      <div><span class="cp-key">starting-balance</span>: <span class="cp-val">100</span></div>
      <div><span class="cp-key">min-money</span>: <span class="cp-val">-10000</span></div>
      <div class="cp-comment"># Spawn settings</div>
      <div><span class="cp-key">spawn-on-join</span>: <span class="cp-val">false</span></div>
    </div>
    <div class="cp-editor-footer">
      <button class="cp-btn">Discard</button>
      <button class="cp-btn cp-btn-primary">Save</button>
    </div>
  </div>

</div>
