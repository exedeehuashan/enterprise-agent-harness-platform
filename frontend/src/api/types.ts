export type Role = 'VIEWER' | 'OPERATOR' | 'APPROVER';

export interface Identity { name: string; roles: Role[] }

export interface Page<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }

export type TaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface Task {
  id: string; title: string; description: string | null; status: TaskStatus;
  createdAt: string; updatedAt: string; version: number;
}

export interface RunStep { sequence: number; tool: string; status: string; startedAt: string | null; finishedAt: string | null; output: string | null }

export interface Run {
  id: string; taskId: string; tool: string; status: string; snapshotTitle: string; snapshotDescription: string | null;
  timeoutSeconds: number; createdAt: string; startedAt: string | null; finishedAt: string | null; deadlineAt: string;
  attemptCount: number; cancelRequested: boolean; errorCode: string | null; steps: RunStep[];
  toolVersion: number; target: string; inputDigest: string; approvalId: string | null;
  arguments: Record<string, unknown> | null;
}

export interface PlanStep { tool: string; toolVersion: number; target: string; timeoutSeconds: number }

export interface ModelCall {
  id: string; taskId: string; provider: string; model: string; requestedBy: string; status: string;
  snapshotTitle: string; snapshotDescription: string | null; inputDigest: string; reservedTokens: number;
  inputTokens: number | null; outputTokens: number | null; costUsd: number; createdAt: string;
  finishedAt: string | null; errorCode: string | null;
  plan: { schemaVersion: number; summary: string; steps: PlanStep[] } | null;
}

export interface ExecutionStep {
  sequence: number; tool: string; toolVersion: number; target: string; timeoutSeconds: number;
  status: string; runId: string | null; runStatus: string | null;
}

export interface PlanExecution {
  id: string; taskId: string; modelCallId: string; status: string; requestedBy: string; planDigest: string;
  errorCode: string | null; createdAt: string; finishedAt: string | null; steps: ExecutionStep[];
}

export interface Approval {
  id: string; runId: string; inputDigest: string; status: string; requestedAt: string; expiresAt: string;
  decidedAt: string | null; decidedBy: string | null; reasonCode: string | null;
}

export interface AuditEvent { id: number; actor: string; action: string; runId: string; occurredAt: string; code: string | null }

export interface Invocation {
  id: string; runId: string; attempt: number; inputDigest: string; status: string;
  startedAt: string; finishedAt: string | null; errorCode: string | null;
}

export interface Artifact {
  id: string; runId: string; invocationId: string; attempt: number; name: string; mediaType: string;
  sizeBytes: number; sha256: string; createdAt: string;
}

export interface QaSuite {
  projectKey: string; key: string; name: string; description: string | null; runnerImage: string; command: string[];
  reportDirs: string[]; network: string; dockerAccess: boolean; maxTimeoutSeconds: number; enabled: boolean;
}

export interface QaProject {
  key: string; name: string; repositoryUrl: string; defaultRef: string; enabled: boolean; suites: QaSuite[];
  repositorySuitesEnabled: boolean; suitesFile: string; allowedImages: string[]; allowDockerAccess: boolean;
  allowNetworkEgress: boolean; maxTimeoutSeconds: number;
}

/** A suite as it will run at a commit: from the registry or the project's repository file. */
export interface SuiteDefinition {
  source: 'registry' | 'repository'; key: string; name: string; description: string | null; image: string; command: string[];
  reportDirs: string[]; network: string; dockerAccess: boolean; maxTimeoutSeconds: number;
}

export interface SuiteCatalog {
  project: string; ref: string; commit: string; repositoryFile: 'found' | 'absent' | 'disabled' | 'invalid';
  repositoryError: string | null; suites: SuiteDefinition[];
}

/** summary.json written by the QA suite runner. */
export interface QaSummary {
  project: string; suite: string; ref: string; commit: string; runnerImage: string; command: string[]; dockerAccess: boolean;
  suiteSource?: 'registry' | 'repository'; exitCode: number | null; stopped: boolean; timedOut: boolean; outcome: string; startedAt: string; finishedAt: string;
  durationSeconds: number; tests: number; failures: number; errors: number; skipped: number; reportFiles: number;
  unreadableReportFiles: number; reportsTruncated: boolean;
  failedTests: { suite: string; name: string; kind: string; message: string }[];
}

export interface PullRequestRun {
  id: string; projectKey: string; prNumber: number; headSha: string; suiteKey: string; prTitle: string; prUrl: string; headRef: string;
  status: 'PENDING' | 'STARTED' | 'SUPERSEDED' | 'SKIPPED'; skipReason: string | null; taskId: string | null; runId: string | null;
  runStatus: string | null; runErrorCode: string | null; createdAt: string; startedAt: string | null; baseSha: string | null;
}

export interface ChangeImpact {
  projectKey: string; prNumber: number; baseSha: string | null; headSha: string; computedAt: string | null; error: string | null;
  impact: {
    buildSystem: string; changedFileCount: number; changedFiles: string[]; truncated: boolean; wholeBuild: boolean;
    modules: { dir: string; coordinates: string; reason: 'CHANGED' | 'DEPENDS_ON_CHANGED' | 'BUILD_CHANGED'; changedFiles: number; testClasses: number }[];
    testClasses: string[]; filesOutsideModules: string[]; notes: string[];
  } | null;
}

export interface GitHubPoll { projectKey: string; lastPolledAt: string; lastSuccessAt: string | null; openPullRequests: number | null; lastError: string | null }

export interface TestHistory {
  label: 'FIRST_SEEN' | 'NEW_FAILURE' | 'PERSISTENT' | 'FLAKY'; previousStatus: string | null; previousCommit: string | null;
  lastPassedCommit: string | null; recentRuns: number; recentFailures: number;
}

export interface Triage {
  runId: string; ingested: boolean; outcome: string | null; category: string | null; explanation: string | null; tests: number; failed: number;
  failures: { testClass: string; testName: string; status: string; failureType: string | null; message: string | null; category: string;
    explanation: string | null; history: TestHistory }[];
  error: string | null;
}

export interface Tool { name: string; version: number; target: string; enabled: boolean; requiresApproval: boolean; retryable: boolean }
