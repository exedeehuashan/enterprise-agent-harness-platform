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
  sizeBytes: number; sha256: string; createdAt: string; expiredAt: string | null;
}

export interface StorageReport {
  tableBytes: Record<string, number>; liveArtifacts: number; liveArtifactBytes: number; expiredArtifacts: number; artifactDays: number; testResultDays: number;
  lastRun: { id: number; actor: string; startedAt: string; artifactsExpired: number; bytesFreed: number; testResultsDeleted: number } | null;
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
  /** Variable name → service name: the suite runs against an environment. */
  environment?: Record<string, string>;
}

export interface SuiteCatalog {
  project: string; ref: string; commit: string; repositoryFile: 'found' | 'absent' | 'disabled' | 'invalid';
  repositoryError: string | null; suites: SuiteDefinition[];
}

/** summary.json written by the QA suite runner. */
export interface QaSummary {
  project: string; suite: string; ref: string; commit: string; runnerImage?: string; command?: string[]; dockerAccess?: boolean; environment?: string;
  suiteSource?: 'registry' | 'repository' | 'api-tests'; exitCode: number | null; stopped: boolean; timedOut: boolean; outcome: string; startedAt: string; finishedAt: string;
  durationSeconds: number; tests: number; failures: number; errors: number; skipped: number; reportFiles: number;
  unreadableReportFiles: number; reportsTruncated: boolean;
  failedTests: { suite: string; name: string; kind: string; message: string }[];
}

export interface PullRequestRun {
  id: string; projectKey: string; prNumber: number; headSha: string; suiteKey: string; prTitle: string; prUrl: string; headRef: string;
  status: 'PENDING' | 'STARTED' | 'SUPERSEDED' | 'SKIPPED'; skipReason: string | null; taskId: string | null; runId: string | null;
  runStatus: string | null; runErrorCode: string | null; createdAt: string; startedAt: string | null; baseSha: string | null;
  /** How much of a selective suite ran: only the affected Maven modules, all of it, or nothing (no module affected). */
  selection?: 'FULL' | 'MODULES' | 'NOT_AFFECTED' | null; selectionReason?: string | null; selectedModules?: string[];
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

export interface Gate {
  project: string; commit: string; verdict: 'PASS' | 'FAIL' | 'PENDING' | 'NOT_CONFIGURED';
  suites: { suite: string; status: string; runId: string | null; outcome: string | null; category: string | null; reason: string | null; modules?: string[]; lineCoverage?: number | null; p95Ms?: number | null }[];
  reasons: string[]; notes: string[]; policy: { requiredSuites: string[] } | null;
}

export interface QualityDashboard {
  project: string; days: number; runs: number; passed: number; passRate: number | null;
  suites: { suite: string; runs: number; passed: number; passRate: number | null; lastOutcome: string | null; lastCommit: string; lastAt: string;
    daily: { day: string; runs: number; passed: number }[] }[];
  failingNow: FailingTest[];
  flaky: { suite: string; testClass: string; testName: string; flakyCommits: number }[];
  slowest: { suite: string; testClass: string; testName: string; averageMs: number; runs: number }[];
  pullRequests: { prNumber: number; title: string; url: string; headSha: string; gate: string; affectedModules: number; impactNotes: number }[];
  gate: { requiredSuites: string[]; performance?: Record<string, PerformanceLimit>; minNewCodeCoverage?: Record<string, number> } | null;
}

export interface ProjectAdmin { project: QaProject; triggers: { suite: string; enabled: boolean }[]; gateSuites: string[]; gateCoverage?: Record<string, number>;
  gatePerformance?: Record<string, PerformanceLimit>; gateNewCodeCoverage?: Record<string, number> }
export interface CoveragePoint {
  runId: string; suite: string; commit: string; outcome: string | null; at: string; linePercent: number | null; branchPercent: number | null;
  linesCovered: number; linesMissed: number;
}
export interface NewCodeCoverage {
  project: string; suite: string; base: string; head: string; status: 'COMPUTED' | 'NO_RUN' | 'NO_LINE_DATA' | 'NO_CHANGED_LINES'; runId: string | null;
  changedFiles: number; covered: number; missed: number; percent: number | null;
  files: { path: string; covered: number; missed: number; uncoveredLines: number[] }[];
}
export interface QuarantineSuggestion {
  id: string; projectKey: string; suiteKey: string; testClass: string; testName: string; flakyCommits: number;
  evidence: { windowDays: number; commits: { commit: string; passed: number; failed: number; lastAt: string }[]; lastFailure: string | null };
  status: 'PENDING' | 'ACCEPTED' | 'DISMISSED' | 'RESOLVED'; createdAt: string; decidedBy: string | null; decidedAt: string | null;
  decisionNote: string | null; quarantineId: string | null;
}
export interface BackupDrill {
  id: string; trigger: 'SCHEDULE' | 'MANUAL'; actor: string; status: 'RUNNING' | 'PASSED' | 'FAILED' | 'SKIPPED'; startedAt: string; finishedAt: string | null;
  schemaVersion: number | null; expectedRows: Record<string, number> | null; restoredRows: Record<string, number> | null; dumpBytes: number | null; error: string | null;
}
export interface AutoFix {
  id: string; projectKey: string; suiteKey: string; sourceRunId: string; sourceCommit: string; trigger: 'MANUAL' | 'AUTO';
  status: 'CODING' | 'NO_CHANGE' | 'CODE_FAILED' | 'VERIFYING' | 'FIXED' | 'PARTLY_FIXED' | 'NOT_FIXED' | 'VERIFY_FAILED';
  codeTaskId: string; codeRunId: string; branch: string | null; pullRequestUrl: string | null; prNumber: number | null; verifyCommit: string | null;
  verifyRunId: string | null; result: { outcome: string; fixed?: number; stillFailing?: number; newFailures?: number } | null; error: string | null;
  createdBy: string; createdAt: string; updatedAt: string;
}
export interface PerformanceLimit { maxP95Ms?: number | null; maxErrorRate?: number | null; maxP95IncreasePercent?: number | null }
export interface PerformancePoint {
  runId: string; suite: string; commit: string; outcome: string | null; at: string; requests: number; requestsPerSecond: number;
  avgMs: number; p90Ms: number; p95Ms: number; maxMs: number; errorRate: number | null; checksRate: number | null;
}
export interface AdminEvent { id: number; actor: string; action: string; projectKey: string; subject: string | null; before: unknown; after: unknown; occurredAt: string }

export interface QaSchedule {
  id: string; projectKey: string; suiteKey: string; ref: string; kind: 'ON_CHANGE' | 'CRON'; cron: string | null; enabled: boolean; taskId: string | null;
  lastCommit: string | null; lastRunId: string | null; lastRunStatus: string | null; lastCheckedAt: string | null; lastError: string | null; nextRunAt: string | null; environment?: string | null;
}

export interface Tool { name: string; version: number; target: string; enabled: boolean; requiresApproval: boolean; retryable: boolean }

export interface FailingTest { suite: string; testClass: string; testName: string; category: string | null; consecutiveFailures: number; failingSince: string | null; failingSinceAt: string | null }

/** A test whose failures do not block the release gate until it expires or is released. */
export interface Quarantine {
  id: string; projectKey: string; suiteKey: string; testClass: string; testName: string; reason: string; createdBy: string;
  createdAt: string; expiresAt: string; releasedAt: string | null; releasedBy: string | null; active: boolean;
}

/** A commit's QA report: the gate verdict and a Markdown summary of suites, failures and pull request selection. */
export interface QaReport { project: string; ref: string; commit: string; verdict: string; generatedAt: string; markdown: string }

/** Test-level difference of one suite between two commits. */
export interface SuiteDiff {
  suite: string; status: 'COMPARED' | 'NO_BASE_RESULT' | 'NO_HEAD_RESULT';
  base: { runId: string; outcome: string | null; tests: number; scope: string } | null;
  head: { runId: string; outcome: string | null; tests: number; scope: string } | null;
  sameScope: boolean; newFailures: string[]; fixed: string[]; stillFailing: string[]; added: string[]; removed: string[];
  newFailureCount: number; fixedCount: number; stillFailingCount: number; addedCount: number; removedCount: number;
}

/** A deployed system under test: service names and the base URLs API tests may call. */
export interface QaEnvironment { projectKey: string; key: string; name: string; services: Record<string, string>; enabled: boolean; network?: string | null; ephemeral?: boolean }
export interface EnvironmentTemplate {
  key: string; name: string; compose: string[]; services: { name: string; composeService: string; port: number }[];
  apiTests: string[]; startupTimeoutSeconds: number; ttlMinutes: number;
}
export interface EnvironmentTemplates {
  project: string; ref: string; commit: string; file: 'found' | 'absent' | 'disabled' | 'invalid'; error: string | null; templates: EnvironmentTemplate[];
}
export interface TemporaryEnvironment {
  id: string; projectKey: string; template: string; commit: string; environmentKey: string; composeProject: string; network: string | null;
  status: 'STARTING' | 'RUNNING' | 'STOPPED' | 'FAILED'; runId: string | null; error: string | null; createdAt: string; expiresAt: string; stoppedAt: string | null;
}
export interface ApiTestCatalog {
  project: string; ref: string; commit: string; file: 'found' | 'absent' | 'disabled' | 'invalid'; error: string | null;
  collections: { key: string; name: string; description: string | null; service: string; cases: number }[];
  environments: QaEnvironment[];
}

export interface Webhook {
  id: string; name: string; format?: WebhookFormat; url: string; events: string[]; enabled: boolean; cursorId: number; consecutiveFailures: number;
  nextAttemptAt: string | null; lastDeliveryAt: string | null; lastError: string | null; createdBy: string; createdAt: string;
}
export type WebhookFormat = 'generic' | 'slack' | 'dingtalk' | 'wecom' | 'feishu' | 'email';
export interface WebhookCreated { subscription: Webhook; secret: string | null }
export interface WebhookDelivery { id: number; auditEventId: number; action: string; statusCode: number | null; error: string | null; durationMs: number | null; attemptedAt: string }

export interface ApiToken {
  id: string; name: string; description: string | null; roles: string[]; createdBy: string; createdAt: string; expiresAt: string | null;
  lastUsedAt: string | null; revokedAt: string | null; revokedBy: string | null; active: boolean;
}
export interface ApiTokenIssued { token: ApiToken; secret: string }

export interface DomainModuleInfo { key: string; name: string; description: string; enabled: boolean; canDisable: boolean; tools: { name: string; enabled: boolean }[]; changedBy: string | null }

export interface GitHubWriteBackView {
  settings: { project: string; enabled: boolean; tokenConfigured: boolean; changedBy: string | null };
  recent: { id: number; kind: string; commitSha: string; prNumber: number | null; status: string; attempts: number; lastError: string | null; createdAt: string; sentAt: string | null }[];
}

export interface AiAnalysis {
  id: string; runId: string; provider: string; model: string; inputTokens: number; outputTokens: number; createdBy: string; createdAt: string;
  analysis: { summary: string; likelyCause: string; category: string; confidence: 'low' | 'medium' | 'high'; suggestedActions: string[]; affectedTests: string[] };
}
export interface AiStatus { provider: string; model: string; dailyTokenBudget: number; usedToday: number }

/** An API test collection drafted by the AI from an OpenAPI document; only valid drafts can run. */
export interface AiTestDraft {
  id: string; projectKey: string; commitSha: string; specPath: string; service: string; instructions: string | null;
  collection: { key: string; name: string; service: string; cases?: unknown[] } | null; valid: boolean; error: string | null; notes: string | null;
  provider: string; model: string; inputTokens: number; outputTokens: number; createdBy: string; createdAt: string;
}

export interface CodeRepository { key: string; name: string; repositoryUrl: string; defaultRef: string; enabled: boolean; allowPush: boolean; changedBy: string }
