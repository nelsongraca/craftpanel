# Server Console

Console tab on the server detail page. Connects to the container stdin/stdout via WebSocket.

<style>
.cp-tabs { display: flex; border-bottom: 2px solid var(--md-default-fg-color--lightest); margin-bottom: 20px; gap: 0; }
.cp-tab { padding: 8px 16px; font-size: 13px; cursor: pointer; color: var(--md-default-fg-color--light); border-bottom: 2px solid transparent; margin-bottom: -2px; }
.cp-tab.active { color: var(--md-primary-fg-color); border-bottom-color: var(--md-primary-fg-color); font-weight: bold; }
.cp-console-wrap { border: 1px solid var(--md-default-fg-color--lightest); border-radius: 6px; overflow: hidden; }
.cp-console-toolbar { background: var(--md-default-fg-color--lightest); padding: 8px 12px; display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
.cp-console-status { display: flex; align-items: center; gap: 6px; }
.cp-console-body { background: #1a1a1a; color: #d4d4d4; font-family: "Courier New", monospace; font-size: 12px; padding: 12px; height: 420px; overflow-y: auto; line-height: 1.6; }
.cp-log-info { color: #d4d4d4; }
.cp-log-warn { color: #E67E22; }
.cp-log-error { color: #E74C3C; }
.cp-log-join { color: #27AE60; }
.cp-log-cmd { color: #2980B9; }
.cp-log-system { color: #8e8e8e; }
.cp-console-input-wrap { display: flex; border-top: 1px solid var(--md-default-fg-color--lightest); }
.cp-console-input { flex: 1; background: var(--md-default-bg-color); border: none; padding: 10px 14px; font-family: "Courier New", monospace; font-size: 13px; color: var(--md-default-fg-color); outline: none; }
.cp-console-send { background: var(--md-primary-fg-color); color: white; border: none; padding: 10px 16px; cursor: pointer; font-size: 13px; }
.cp-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: bold; }
.cp-badge-healthy { background: #d4edda; color: #155724; }
.cp-btn { border-radius: 4px; padding: 5px 10px; font-size: 12px; cursor: pointer; background: var(--md-default-bg-color); color: var(--md-default-fg-color); border: 1px solid var(--md-default-fg-color--lightest); }
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
      </div>
    </div>
    <div style="display: flex; gap: 8px;">
      <button class="cp-btn">↺ Restart</button>
      <button class="cp-btn">■ Stop</button>
      <button class="cp-btn">···</button>
    </div>
  </div>

  <!-- Tabs -->
  <div class="cp-tabs">
    <div class="cp-tab">Overview</div>
    <div class="cp-tab active">Console</div>
    <div class="cp-tab">Files</div>
    <div class="cp-tab">Mods</div>
    <div class="cp-tab">Backups</div>
    <div class="cp-tab">Configuration</div>
  </div>

  <!-- Console -->
  <div class="cp-console-wrap">
    <div class="cp-console-toolbar">
      <div class="cp-console-status">
        <span style="color: #27AE60;">●</span>
        <span>Connected to container</span>
        <span class="cp-muted">· survival_container · node-1</span>
      </div>
      <div style="display: flex; gap: 8px;">
        <button class="cp-btn">⬇ Scroll to bottom</button>
        <button class="cp-btn">🗑 Clear</button>
      </div>
    </div>
    <div class="cp-console-body">
      <div class="cp-log-system">[10:00:00] Starting minecraft server version 1.21.4</div>
      <div class="cp-log-info">[10:00:01] [Server thread/INFO]: Loading properties</div>
      <div class="cp-log-info">[10:00:01] [Server thread/INFO]: Default game type: SURVIVAL</div>
      <div class="cp-log-info">[10:00:02] [Server thread/INFO]: Preparing level "world"</div>
      <div class="cp-log-info">[10:00:04] [Server thread/INFO]: Preparing start region for dimension minecraft:overworld</div>
      <div class="cp-log-info">[10:00:05] [Server thread/INFO]: Done (3.421s)! For help, type "help"</div>
      <div class="cp-log-join">[10:00:12] [Server thread/INFO]: Notch joined the game</div>
      <div class="cp-log-join">[10:00:15] [Server thread/INFO]: jeb_ joined the game</div>
      <div class="cp-log-info">[10:01:00] [Server thread/INFO]: &lt;Notch&gt; Hello everyone!</div>
      <div class="cp-log-warn">[10:02:14] [Server thread/WARN]: Can't keep up! Is the server overloaded?</div>
      <div class="cp-log-join">[10:03:01] [Server thread/INFO]: Dinnerbone joined the game</div>
      <div class="cp-log-info">[10:03:45] [Server thread/INFO]: &lt;jeb_&gt; hey Dinnerbone</div>
      <div class="cp-log-cmd">[10:04:00] [Server thread/INFO]: [admin: Gave 1 diamond_sword to Notch]</div>
      <div class="cp-log-error">[10:05:33] [Server thread/ERROR]: Encountered an unexpected exception</div>
      <div class="cp-log-error">[10:05:33] [Server thread/ERROR]: java.lang.NullPointerException: Cannot invoke method getWorld()</div>
      <div class="cp-log-info">[10:05:34] [Server thread/INFO]: Server recovered from exception</div>
      <div class="cp-log-join">[10:06:00] [Server thread/INFO]: Dream joined the game</div>
      <div class="cp-log-info">[10:07:22] [Server thread/INFO]: &lt;Dream&gt; anyone want to play?</div>
      <div class="cp-log-info">[10:08:00] [Server thread/INFO]: Saving the game</div>
      <div class="cp-log-info">[10:08:01] [Server thread/INFO]: Saved the game</div>
      <div class="cp-log-cmd" style="background: rgba(41,128,185,0.1); padding: 1px 4px;">[10:09:00] &gt; say Hello world</div>
      <div class="cp-log-info">[10:09:00] [Server thread/INFO]: [Server] Hello world</div>
      <div class="cp-log-info">[10:09:45] [Server thread/INFO]: &lt;Notch&gt; lol</div>
    </div>
    <div class="cp-console-input-wrap">
      <span style="padding: 10px 8px 10px 14px; font-family: monospace; font-size: 13px; color: var(--md-primary-fg-color);">&gt;</span>
      <input class="cp-console-input" placeholder="Enter command..." />
      <button class="cp-console-send">Send</button>
    </div>
  </div>

</div>
