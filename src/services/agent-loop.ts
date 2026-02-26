/**
 * Agent Loop - Core orchestration for the observe-think-act cycle.
 *
 * Receives widget state from the Chrome extension via WebSocket,
 * uses Claude to decide the next action, validates it against policy,
 * and sends it back to the extension for execution.
 */

import type { WebSocket } from 'ws';
import type { CaseStore } from '../domain/case-store.js';
import { ClaudeClient, type WidgetState, type AgentAction, type CaseContext } from './claude-client.js';
import { evaluateActionPolicy, type ActionPolicyResult } from './policy.js';
import { filterSensitiveData } from './sensitive-filter.js';

// ─── Constants ──────────────────────────────────────────────────────────────

const MAX_ITERATIONS = 30;
const MIN_ACTION_INTERVAL_MS = 2000;
const WAIT_TIMEOUT_MS = 45000;
const STATE_WAIT_TIMEOUT_MS = 30000;

// ─── Types ──────────────────────────────────────────────────────────────────

interface AgentSession {
  caseId: string;
  ws: WebSocket;
  context: CaseContext;
  iteration: number;
  lastActionAt: number;
  paused: boolean;
  stopped: boolean;
  waitingForState: boolean;
  stateResolve: ((state: WidgetState) => void) | null;
  stateTimeout: ReturnType<typeof setTimeout> | null;
}

interface AgentLoopDeps {
  store: CaseStore;
}

// ─── Agent Loop Manager ─────────────────────────────────────────────────────

export class AgentLoopManager {
  private readonly sessions = new Map<string, AgentSession>();
  private readonly store: CaseStore;
  private claudeClient: ClaudeClient | null = null;

  constructor(deps: AgentLoopDeps) {
    this.store = deps.store;
  }

  private getClaudeClient(): ClaudeClient {
    if (!this.claudeClient) {
      this.claudeClient = new ClaudeClient();
    }
    return this.claudeClient;
  }

  /**
   * Start a new agent session for a case.
   */
  startSession(caseId: string, ws: WebSocket, context: CaseContext): void {
    if (this.sessions.has(caseId)) {
      this.stopSession(caseId);
    }

    const session: AgentSession = {
      caseId,
      ws,
      context,
      iteration: 0,
      lastActionAt: 0,
      paused: false,
      stopped: false,
      waitingForState: false,
      stateResolve: null,
      stateTimeout: null,
    };

    this.sessions.set(caseId, session);
    this.log(caseId, 'Agent session started');

    // Request initial widget state
    this.requestWidgetState(session);
  }

  /**
   * Feed new widget state into a session (called when extension sends state).
   */
  onWidgetState(caseId: string, state: WidgetState): void {
    const session = this.sessions.get(caseId);
    if (!session) return;

    if (session.stateResolve) {
      // Resolve the pending state wait
      if (session.stateTimeout) clearTimeout(session.stateTimeout);
      session.stateResolve(state);
      session.stateResolve = null;
      session.stateTimeout = null;
      session.waitingForState = false;
    } else if (!session.paused && !session.stopped) {
      // Unsolicited state update - run the loop
      void this.runIteration(session, state);
    }
  }

  /**
   * Resume a paused session (user approved or provided input).
   */
  resumeSession(caseId: string, userInput?: string): void {
    const session = this.sessions.get(caseId);
    if (!session) return;

    session.paused = false;

    if (userInput) {
      session.context.previousActions.push(`Human provided input: "${userInput}"`);
    }

    this.log(caseId, 'Session resumed');
    this.requestWidgetState(session);
  }

  /**
   * Pause a session.
   */
  pauseSession(caseId: string): void {
    const session = this.sessions.get(caseId);
    if (!session) return;

    session.paused = true;
    this.log(caseId, 'Session paused by user');
    this.store.updateStatus(caseId, 'paused_for_approval');
  }

  /**
   * Stop a session completely.
   */
  stopSession(caseId: string): void {
    const session = this.sessions.get(caseId);
    if (!session) return;

    session.stopped = true;
    if (session.stateTimeout) clearTimeout(session.stateTimeout);
    this.sessions.delete(caseId);
    this.log(caseId, 'Session stopped');
  }

  /**
   * Handle action execution result from the extension.
   */
  onActionResult(caseId: string, actionId: string, success: boolean, error?: string): void {
    const session = this.sessions.get(caseId);
    if (!session) return;

    if (!success) {
      this.log(caseId, `Action failed: ${error}`);
      session.context.previousActions.push(`[FAILED] ${error}`);
    }
  }

  // ─── Core loop ──────────────────────────────────────────────────────────

  private async runIteration(session: AgentSession, state: WidgetState): Promise<void> {
    if (session.paused || session.stopped) return;

    // Guard: max iterations
    if (session.iteration >= MAX_ITERATIONS) {
      this.endSession(session, 'error', 'Maximum iterations reached without resolution');
      return;
    }

    // Guard: rate limit
    const elapsed = Date.now() - session.lastActionAt;
    if (elapsed < MIN_ACTION_INTERVAL_MS) {
      await sleep(MIN_ACTION_INTERVAL_MS - elapsed);
    }

    session.iteration++;
    session.lastActionAt = Date.now();

    try {
      // Filter sensitive data before sending to LLM
      const filteredState = filterSensitiveData(state);

      // Ask Claude for the next action
      const claude = this.getClaudeClient();
      const decision = await claude.decideNextAction(filteredState, session.context);

      this.log(session.caseId, `Iteration ${session.iteration}: ${decision.action.type}`, {
        reasoning: decision.reasoning.slice(0, 200),
      });

      // Validate against policy
      const policyResult = evaluateActionPolicy(decision.action);
      if (policyResult.blocked) {
        this.sendToPopup(session, {
          type: 'paused',
          reason: policyResult.reason ?? 'Action blocked by policy',
        });
        session.paused = true;
        this.store.updateStatus(session.caseId, 'paused_for_approval');
        return;
      }

      if (policyResult.requiresApproval) {
        this.sendToPopup(session, {
          type: 'paused',
          reason: policyResult.reason ?? 'This action needs your approval',
          needsInput: false,
        });
        session.paused = true;
        this.store.updateStatus(session.caseId, 'paused_for_approval');
        return;
      }

      // Execute the action
      await this.executeAction(session, decision.action);
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : String(error);
      console.error(`[AgentLoop] Error in iteration ${session.iteration}:`, msg);

      // Retry on transient errors, but not more than 3 in a row
      if (session.iteration < MAX_ITERATIONS) {
        this.log(session.caseId, `Error: ${msg}. Will retry on next state update.`);
      } else {
        this.endSession(session, 'error', msg);
      }
    }
  }

  private async executeAction(session: AgentSession, action: AgentAction): Promise<void> {
    const caseId = session.caseId;

    switch (action.type) {
      case 'type_message': {
        session.context.previousActions.push(`Sent message: "${action.text}"`);
        this.sendToPopup(session, {
          type: 'agent_message',
          text: action.text,
        });
        this.sendToPopup(session, {
          type: 'action',
          message: 'Sending message...',
        });

        this.sendActionToExtension(session, action);
        // After sending, wait for the next state (response)
        await this.waitForNewState(session, WAIT_TIMEOUT_MS);
        break;
      }

      case 'click_button': {
        session.context.previousActions.push(`Clicked: "${action.buttonLabel}"`);
        this.sendToPopup(session, {
          type: 'action',
          message: `Clicking "${action.buttonLabel}"`,
        });

        this.sendActionToExtension(session, action);
        await this.waitForNewState(session, 5000);
        break;
      }

      case 'upload_file': {
        session.context.previousActions.push(`Uploading: ${action.fileDescription}`);
        this.sendToPopup(session, {
          type: 'action',
          message: `Uploading ${action.fileDescription}`,
        });

        this.sendActionToExtension(session, action);
        await this.waitForNewState(session, 5000);
        break;
      }

      case 'wait': {
        session.context.previousActions.push(`Waiting: ${action.reason}`);
        this.sendToPopup(session, { type: 'waiting' });

        // Tell extension to wait and watch for DOM changes
        this.sendActionToExtension(session, {
          type: 'wait',
          durationMs: Math.min(action.durationMs, WAIT_TIMEOUT_MS),
          reason: action.reason,
        });

        await this.waitForNewState(session, Math.min(action.durationMs, WAIT_TIMEOUT_MS) + 5000);
        break;
      }

      case 'request_human_review': {
        session.context.previousActions.push(`Requested human review: ${action.reason}`);
        this.sendToPopup(session, {
          type: 'paused',
          reason: action.reason,
          needsInput: action.needsInput,
          inputPrompt: action.inputPrompt,
        });

        session.paused = true;
        this.store.updateStatus(caseId, 'paused_for_approval');
        this.store.appendEvent(caseId, {
          type: 'policy_gate',
          message: `Agent requested human review: ${action.reason}`,
        });
        break;
      }

      case 'mark_resolved': {
        session.context.previousActions.push(`Resolved: ${action.summary}`);
        this.sendToPopup(session, {
          type: 'completed',
          summary: action.summary,
        });

        this.store.complete(caseId, action.summary);
        this.endSession(session, 'completed', action.summary);
        break;
      }
    }
  }

  // ─── State management ───────────────────────────────────────────────────

  private requestWidgetState(session: AgentSession): void {
    this.sendWs(session.ws, {
      type: 'request_widget_state',
      caseId: session.caseId,
    });
  }

  private waitForNewState(session: AgentSession, timeoutMs: number): Promise<WidgetState | null> {
    return new Promise((resolve) => {
      session.waitingForState = true;
      session.stateResolve = (state: WidgetState) => {
        // Received state - run next iteration
        void this.runIteration(session, state);
        resolve(state);
      };

      session.stateTimeout = setTimeout(() => {
        session.waitingForState = false;
        session.stateResolve = null;
        session.stateTimeout = null;

        // Timed out waiting - request state explicitly
        if (!session.paused && !session.stopped) {
          this.requestWidgetState(session);
        }
        resolve(null);
      }, timeoutMs);
    });
  }

  // ─── Communication helpers ──────────────────────────────────────────────

  private sendActionToExtension(session: AgentSession, action: AgentAction): void {
    const actionId = `${session.caseId}-${session.iteration}`;
    this.sendWs(session.ws, {
      type: 'execute_action',
      caseId: session.caseId,
      actionId,
      action,
    });

    this.store.appendEvent(session.caseId, {
      type: 'automation_step',
      message: `Action: ${action.type}`,
      meta: { iteration: session.iteration },
    });
  }

  private sendToPopup(session: AgentSession, event: Record<string, unknown>): void {
    this.sendWs(session.ws, {
      type: 'agent_event',
      caseId: session.caseId,
      event,
    });
  }

  private endSession(
    session: AgentSession,
    outcome: 'completed' | 'error',
    message: string,
  ): void {
    if (outcome === 'error') {
      this.sendWs(session.ws, {
        type: 'case_error',
        caseId: session.caseId,
        message,
      });
      this.store.fail(session.caseId, message);
    } else {
      this.sendWs(session.ws, {
        type: 'case_completed',
        caseId: session.caseId,
        summary: message,
      });
    }

    this.stopSession(session.caseId);
  }

  private sendWs(ws: WebSocket, data: Record<string, unknown>): void {
    if (ws.readyState === 1) {
      // WebSocket.OPEN
      ws.send(JSON.stringify(data));
    }
  }

  private log(caseId: string, message: string, meta?: Record<string, string | number | boolean>): void {
    console.log(`[AgentLoop:${caseId.slice(0, 8)}] ${message}`);
    this.store.appendEvent(caseId, {
      type: 'automation_step',
      message,
      meta,
    });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
