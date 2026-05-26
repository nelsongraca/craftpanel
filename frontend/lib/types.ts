export interface Server {
  id: string;
  name: string;
  display_name: string;
  description?: string;
  server_type: string;
  status: string;
  node_id: string;
  network_id?: string;
  game_port: number;
  memory_mb: number;
  cpu_shares: number;
  exposed_externally: boolean;
  public_subdomain?: string;
  is_migrating: boolean;
  config_mode: string;
  created_at: string;
  updated_at: string;
}

export interface Node {
  id: string;
  display_name: string;
  hostname: string;
  public_ip: string;
  private_ip: string;
  status: string;
  total_ram_mb: number;
  total_cpu_shares: number;
  allocated_ram_mb: number;
  allocated_cpu_shares: number;
  port_range_start: number;
  port_range_end: number;
  data_path: string;
  agent_version?: string;
  last_seen_at?: string;
  created_at: string;
}

export interface Network {
  id: string;
  name: string;
  type: string;
  proxy_type?: string;
  proxy_port?: number;
  description?: string;
  server_count: number;
  created_at: string;
}
