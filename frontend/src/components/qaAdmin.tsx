import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { ProjectAdmin, QaProject } from '../api/types';
import { useAction, usePolling } from '../hooks';
import { Empty, ErrorBanner, Section, Time } from './ui';

const lines = (text: string) => text.split('\n').map(s => s.trim()).filter(Boolean);

/** Registers a new project under test. The policy defaults are conservative: no Docker access, 30 minutes. */
export function NewProjectForm({ onCreated }: { onCreated: () => void }) {
  const [form, setForm] = useState({ key: '', name: '', repositoryUrl: '', defaultRef: 'main', images: 'maven:3.9.11-eclipse-temurin-17' });
  const action = useAction();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const created = await action.run(() => api.admin.create({
      key: form.key.trim(), name: form.name.trim(), repositoryUrl: form.repositoryUrl.trim(), defaultRef: form.defaultRef.trim(), allowedImages: lines(form.images),
    }));
    if (created) { setForm({ ...form, key: '', name: '', repositoryUrl: '' }); onCreated(); }
  };
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  return (
    <Section title="Register a project">
      <form className="stack" onSubmit={submit}>
        <div className="row">
          <label className="inline">Key<input value={form.key} onChange={set('key')} placeholder="billing" maxLength={60} required /></label>
          <label className="inline">Name<input value={form.name} onChange={set('name')} maxLength={120} required /></label>
          <label className="inline">Default ref<input value={form.defaultRef} onChange={set('defaultRef')} maxLength={100} required /></label>
        </div>
        <label>Repository URL (https)<input value={form.repositoryUrl} onChange={set('repositoryUrl')} placeholder="https://github.com/owner/repo.git" required /></label>
        <label>Allowed runner images (one per line)<textarea rows={2} value={form.images} onChange={set('images')} /></label>
        <p className="muted small">New projects start without Docker access and with a 30-minute limit. Add suites, triggers and a gate after registering.</p>
        <ErrorBanner error={action.error} />
        <div><button type="submit" disabled={action.busy}>Register project</button></div>
      </form>
    </Section>
  );
}

/** Policy, registry suites, pull request triggers, release gate and change history for one project. */
export function ProjectAdminPanel({ project, onChanged }: { project: QaProject; onChanged: () => void }) {
  const admin = usePolling(() => api.admin.project(project.key), [project.key], 0);
  const events = usePolling(() => api.admin.events(project.key), [project.key], 0);
  const action = useAction();
  const apply = async (call: () => Promise<ProjectAdmin>) => {
    if (await action.run(call)) { void admin.reload(); void events.reload(); onChanged(); }
  };
  if (admin.error) return <ErrorBanner error={admin.error} />;
  if (!admin.data) return null;
  const a = admin.data;
  const suiteKeys = Array.from(new Set([...a.project.suites.map(s => s.key), ...a.triggers.map(t => t.suite)])).sort();
  return (
    <details className="admin">
      <summary>Administer {project.name}</summary>
      <ErrorBanner error={action.error} />
      <PolicyForm project={a.project} busy={action.busy} onSave={body => apply(() => api.admin.update(project.key, body))} />
      <h3 className="subhead">Pull request triggers</h3>
      <div className="row">
        {suiteKeys.map(key => {
          const on = a.triggers.find(t => t.suite === key)?.enabled ?? false;
          return <label key={key} className="inline"><input type="checkbox" checked={on} disabled={action.busy}
            onChange={e => apply(() => api.admin.trigger(project.key, key, e.target.checked))} /> {key}</label>;
        })}
        {suiteKeys.length === 0 && <span className="muted small">No suites yet.</span>}
      </div>
      <GateForm suites={a.gateSuites} coverage={a.gateCoverage ?? {}} busy={action.busy}
        onSave={(list, minimums) => apply(() => list.length ? api.admin.gate(project.key, list, minimums) : api.admin.removeGate(project.key))} />
      <SuiteForm busy={action.busy} onSave={(key, body) => apply(() => api.admin.suite(project.key, key, body))} />
      <h3 className="subhead">Change history</h3>
      {events.data?.length === 0 && <Empty>No changes recorded.</Empty>}
      <ol className="timeline small">
        {events.data?.map(e => <li key={e.id}><Time value={e.occurredAt} /> <strong>{e.actor}</strong> {e.action.replace(/_/g, ' ').toLowerCase()}{e.subject ? ` ${e.subject}` : ''}</li>)}
      </ol>
    </details>
  );
}

function PolicyForm({ project, busy, onSave }: { project: QaProject; busy: boolean; onSave: (body: Record<string, unknown>) => void }) {
  const [p, setP] = useState({ ...project, images: project.allowedImages.join('\n') });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSave({ name: p.name, repositoryUrl: p.repositoryUrl, defaultRef: p.defaultRef, enabled: p.enabled, repositorySuitesEnabled: p.repositorySuitesEnabled,
      suitesFile: p.suitesFile, allowedImages: lines(p.images), allowDockerAccess: p.allowDockerAccess, allowNetworkEgress: p.allowNetworkEgress,
      maxTimeoutSeconds: p.maxTimeoutSeconds });
  };
  return (
    <form className="stack" onSubmit={submit}>
      <h3 className="subhead">Project and policy</h3>
      <div className="row">
        <label className="inline">Name<input value={p.name} onChange={e => setP({ ...p, name: e.target.value })} /></label>
        <label className="inline">Default ref<input value={p.defaultRef} onChange={e => setP({ ...p, defaultRef: e.target.value })} /></label>
        <label className="inline">Max time (s)<input type="number" min={30} max={3600} value={p.maxTimeoutSeconds} onChange={e => setP({ ...p, maxTimeoutSeconds: Number(e.target.value) })} /></label>
      </div>
      <label>Repository URL<input value={p.repositoryUrl} onChange={e => setP({ ...p, repositoryUrl: e.target.value })} /></label>
      <label>Allowed runner images (one per line)<textarea rows={2} value={p.images} onChange={e => setP({ ...p, images: e.target.value })} /></label>
      <div className="row">
        <label className="inline"><input type="checkbox" checked={p.enabled} onChange={e => setP({ ...p, enabled: e.target.checked })} /> enabled</label>
        <label className="inline"><input type="checkbox" checked={p.repositorySuitesEnabled} onChange={e => setP({ ...p, repositorySuitesEnabled: e.target.checked })} /> read suites from</label>
        <input value={p.suitesFile} onChange={e => setP({ ...p, suitesFile: e.target.value })} aria-label="Suites file" />
        <label className="inline"><input type="checkbox" checked={p.allowDockerAccess} onChange={e => setP({ ...p, allowDockerAccess: e.target.checked })} /> allow Docker access</label>
        <label className="inline"><input type="checkbox" checked={p.allowNetworkEgress} onChange={e => setP({ ...p, allowNetworkEgress: e.target.checked })} /> allow network egress</label>
      </div>
      <div><button type="submit" disabled={busy}>Save policy</button></div>
    </form>
  );
}

function GateForm({ suites, coverage, busy, onSave }: { suites: string[]; coverage: Record<string, number>; busy: boolean;
    onSave: (list: string[], minLineCoverage: Record<string, number>) => void }) {
  const [value, setValue] = useState(suites.join(', '));
  const [minimums, setMinimums] = useState(Object.entries(coverage).map(([k, v]) => `${k}=${v}`).join(', '));
  const [invalid, setInvalid] = useState<string | null>(null);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    // "backend-unit=70, frontend-unit=60": minimum line coverage per required suite. A malformed entry stops the save
    // rather than being dropped, which would silently weaken the gate.
    const parsed: Record<string, number> = {};
    for (const part of minimums.split(',').map(p => p.trim()).filter(Boolean)) {
      const m = /^([a-z0-9][a-z0-9-]{1,59})\s*=\s*(\d+(?:\.\d+)?)$/.exec(part);
      if (!m || Number(m[2]) > 100) { setInvalid(`"${part}" is not suite=percentage (0-100)`); return; }
      parsed[m[1]] = Number(m[2]);
    }
    setInvalid(null);
    onSave(value.split(/[,\s]+/).filter(Boolean), parsed);
  };
  return (
    <form className="row" onSubmit={submit}>
      <h3 className="subhead">Release gate</h3>
      <label className="inline">Required suites<input value={value} onChange={e => setValue(e.target.value)} placeholder="empty removes the gate" /></label>
      <label className="inline">Minimum line coverage<input value={minimums} onChange={e => setMinimums(e.target.value)} placeholder="backend-unit=70" /></label>
      <button type="submit" className="secondary" disabled={busy}>Save gate</button>
      {invalid && <div className="error" role="alert">{invalid}</div>}
    </form>
  );
}

function SuiteForm({ busy, onSave }: { busy: boolean; onSave: (key: string, body: Record<string, unknown>) => void }) {
  const empty = { key: '', name: '', image: 'maven:3.9.11-eclipse-temurin-17', command: 'mvn\n-B\n-ntp\nverify', reportDirs: 'target/surefire-reports',
    network: 'egress', dockerAccess: false, maxTimeoutSeconds: 1800, enabled: true };
  const [s, setS] = useState(empty);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSave(s.key.trim(), { name: s.name, image: s.image, command: lines(s.command), reportDirs: lines(s.reportDirs), network: s.network,
      dockerAccess: s.dockerAccess, maxTimeoutSeconds: s.maxTimeoutSeconds, enabled: s.enabled });
  };
  return (
    <form className="stack" onSubmit={submit}>
      <h3 className="subhead">Add or replace a registry suite</h3>
      <div className="row">
        <label className="inline">Key<input value={s.key} onChange={e => setS({ ...s, key: e.target.value })} required /></label>
        <label className="inline">Name<input value={s.name} onChange={e => setS({ ...s, name: e.target.value })} required /></label>
        <label className="inline">Image<input value={s.image} onChange={e => setS({ ...s, image: e.target.value })} required /></label>
      </div>
      <div className="row">
        <label>Command (one argument per line, no shell)<textarea rows={4} value={s.command} onChange={e => setS({ ...s, command: e.target.value })} /></label>
        <label>Report directories (one per line)<textarea rows={4} value={s.reportDirs} onChange={e => setS({ ...s, reportDirs: e.target.value })} /></label>
      </div>
      <div className="row">
        <label className="inline">Network<select value={s.network} onChange={e => setS({ ...s, network: e.target.value })}><option>egress</option><option>none</option></select></label>
        <label className="inline">Max time (s)<input type="number" min={30} max={3600} value={s.maxTimeoutSeconds} onChange={e => setS({ ...s, maxTimeoutSeconds: Number(e.target.value) })} /></label>
        <label className="inline"><input type="checkbox" checked={s.dockerAccess} onChange={e => setS({ ...s, dockerAccess: e.target.checked })} /> needs Docker (approval)</label>
        <label className="inline"><input type="checkbox" checked={s.enabled} onChange={e => setS({ ...s, enabled: e.target.checked })} /> enabled</label>
        <button type="submit" className="secondary" disabled={busy || !s.key.trim()}>Save suite</button>
      </div>
    </form>
  );
}
