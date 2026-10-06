import type { HostState } from './ipc.ts'
import type { SSHState, SSHStatus } from './ssh.ts'

export function isSSHConnecting(status: SSHStatus): boolean {
  return status.kind === 'connecting' || status.kind === 'retrying'
}

/** Failed or idle saved profiles must leave local host recovery actions visible. */
export function showsChat(host: HostState['kind'], computers: number, ssh: SSHState): boolean {
  return host === 'running' || computers > 0 || ssh.profiles.some((p) => isSSHConnecting(ssh.status[p.id] ?? { kind: 'idle' }))
}

export function sshStatusDetail(status: SSHStatus): string {
  switch (status.kind) {
    case 'idle': return 'Not connected'
    case 'connecting': return status.step
    case 'retrying': return `${status.message} Retrying…`
    case 'confirmHostKey': return 'Confirm host key…'
    case 'connected': return 'Connected'
    case 'failed': return status.message
    case 'notInstalled': return 'Host not installed'
  }
}
