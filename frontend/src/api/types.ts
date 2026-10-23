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

export interface Tool { name: string; version: number; target: string; enabled: boolean; requiresApproval: boolean; retryable: boolean }
