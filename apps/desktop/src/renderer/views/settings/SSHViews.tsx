import { useState } from 'react'
import { SSH_INSTALL_COMMAND, type SSHProfile, type SSHStatus } from '@shared/ssh'
import { Button, CardSection, IconButton } from '../../components/Controls'
import { Icon } from '../../components/Icon'
import { ModalHeader, useDismiss } from '../../components/Overlay'
import { font } from '../../lib/fonts'
import { Reveal } from './parts'
import { sshStatusDetail } from '@shared/ssh-startup'
import { sshBridge } from './ssh-model'

// MARK: SSH

export function SSHRow({ profile, status, edit, attached = true }: { profile: SSHProfile; status: SSHStatus; attached?: boolean; edit: () => void }) {
  const bridge = sshBridge()
  const target = `${profile.user ? `${profile.user}@` : ''}${profile.host}${profile.port !== null ? `:${profile.port}` : ''}`
  const line = (() => {
    switch (status.kind) {
      case 'idle':
        return `${target} · Not connected`
      case 'connecting':
        return status.step
      case 'confirmHostKey':
        return `${target} · Confirm the host key`
      case 'connected':
        return `${target} · ${sshStatusDetail(status, attached)}`
      case 'retrying':
        return `${status.message} Retrying…`
      case 'failed':
        return status.message
      case 'notInstalled':
        return `codync-host isn't installed on ${profile.host}. Install it there:`
    }
  })()
  const problem = status.kind === 'retrying' || status.kind === 'failed' || status.kind === 'notInstalled'
  const active = status.kind === 'connected' || status.kind === 'connecting' || status.kind === 'retrying'
  return (
    <div className="settings-card settings-appear">
      <div className="settings-row">
        <span style={{ width: 22, display: 'flex', justifyContent: 'center', color: 'var(--secondary)' }}>
          <Icon name="terminal" size={13} />
        </span>
        <div className="settings-stack" style={{ flex: 1 }}>
          <span style={font('callout', 'medium')}>{profile.name || profile.host}</span>
          <span style={{ ...font('caption'), color: problem ? 'var(--warning)' : 'var(--secondary)' }}>{line}</span>
        </div>
        {active ? (
          <IconButton title="Disconnect" icon="stop.circle" onClick={() => bridge?.disconnect(profile.id)} />
        ) : (
          <IconButton title="Connect" icon="arrow.clockwise" onClick={() => bridge?.connect(profile.id)} />
        )}
        <IconButton title="Edit" icon="pencil" onClick={edit} />
        <IconButton title="Remove" icon="trash" onClick={() => bridge?.remove(profile.id)} />
      </div>
      <Reveal show={status.kind === 'confirmHostKey'}>
        {status.kind === 'confirmHostKey' ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <span style={{ ...font('caption'), color: 'var(--secondary)' }}>
              First connection to {status.name}. Check that its host key fingerprint matches what the computer's owner sees (
              <code style={{ fontFamily: 'var(--mono)' }}>ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub</code>).
            </span>
            {status.fingerprints.map((f) => (
              <span key={f} className="selectable" style={font('caption', undefined, 'monospaced')}>{f}</span>
            ))}
            <div style={{ display: 'flex', gap: 8 }}>
              <Button onClick={() => bridge?.trustHostKey(profile.id)}>Trust and connect</Button>
              <Button kind="secondary" onClick={() => bridge?.disconnect(profile.id)}>Cancel</Button>
            </div>
          </div>
        ) : null}
      </Reveal>
      <Reveal show={status.kind === 'notInstalled'}>
        <div className="settings-row" style={{ gap: 6 }}>
          <span className="settings-code selectable">{SSH_INSTALL_COMMAND}</span>
          <IconButton title="Copy install command" icon="doc.on.doc" onClick={() => window.codync.app.copy(SSH_INSTALL_COMMAND)} />
        </div>
      </Reveal>
    </div>
  )
}

/** Add or change an SSH computer. Only fields ssh takes as separate arguments; nothing goes through a shell. */
export function SSHProfileEditor({ profile: initial, isNew }: { profile: SSHProfile; isNew: boolean }) {
  const dismiss = useDismiss()
  const [profile, setProfile] = useState(initial)
  const [port, setPort] = useState(initial.port?.toString() ?? '')
  const [remotePort, setRemotePort] = useState(String(initial.remotePort))
  const [user, setUser] = useState(initial.user ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const bridge = sshBridge()

  const save = async () => {
    const isInt = (s: string) => /^-?\d+$/.test(s)
    const portText = port.trim()
    if (portText && !isInt(portText)) return setProblem('The SSH port must be a number.')
    const remote = remotePort.trim()
    if (!isInt(remote)) return setProblem('The Codync port must be a number.')
    const u = user.trim()
    const p: SSHProfile = {
      ...profile,
      host: profile.host.trim(),
      name: profile.name.trim(),
      user: u || null,
      port: portText ? Number(portText) : null,
      remotePort: Number(remote),
    }
    const found = bridge ? await bridge.save(p) : 'SSH computers need a newer build of Codync.'
    if (found) return setProblem(found)
    dismiss()
  }

  const field = (label: string, value: string, set: (v: string) => void, prompt: string) => (
    <div className="settings-row" style={{ gap: 12 }}>
      <span style={{ color: 'var(--text)' }}>{label}</span>
      <input className="settings-field" aria-label={label} placeholder={prompt} value={value} spellCheck={false} onChange={(e) => set(e.target.value)} />
    </div>
  )

  return (
    // Return in a field saves (the default action).
    <div style={{ width: 460, display: 'flex', flexDirection: 'column' }} onKeyDown={(e) => e.key === 'Enter' && e.target instanceof HTMLInputElement && void save()}>
      <ModalHeader title={isNew ? 'Add SSH computer' : 'Edit SSH computer'} trailing={<IconButton title={isNew ? 'Add' : 'Save'} icon="checkmark" onClick={() => void save()} />} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: '4px 20px 20px' }}>
        <CardSection>
          {field('Name', profile.name, (name) => setProfile({ ...profile, name }), 'Optional')}
          {field('Host', profile.host, (host) => setProfile({ ...profile, host }), 'SSH alias or hostname')}
          {field('User', user, setUser, 'From SSH config')}
          {field('SSH port', port, setPort, 'From SSH config')}
          <div className="settings-row">
            <span>Key</span>
            <span style={{ flex: 1 }} />
            <span style={{ color: 'var(--secondary)' }}>{profile.identityFile?.split('/').pop() ?? 'ssh-agent / SSH config'}</span>
            <IconButton
              title="Choose key file"
              icon="key"
              onClick={() => void bridge?.chooseKey().then((path) => path && setProfile((p) => ({ ...p, identityFile: path })))}
            />
            {profile.identityFile ? (
              <span className="settings-appear" style={{ display: 'flex' }}>
                <IconButton title="Use ssh-agent" icon="xmark.circle" onClick={() => setProfile({ ...profile, identityFile: null })} />
              </span>
            ) : null}
          </div>
          {field('Codync port', remotePort, setRemotePort, '')}
        </CardSection>
        <span style={{ ...font('caption'), color: 'var(--secondary)' }}>
          Codync opens an SSH tunnel to codync-host on that computer's loopback. It uses your SSH config and ssh-agent; agent forwarding stays off.
        </span>
        <Reveal show={problem !== null}>
          <span style={{ ...font('caption'), color: 'var(--danger)' }}>{problem}</span>
        </Reveal>
      </div>
    </div>
  )
}
