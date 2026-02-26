/**
 * LLM client for the agent loop.
 *
 * Uses Azure OpenAI (GPT-5 Nano) with tool-use for decision making
 * when navigating customer support chat widgets.
 */

import { AzureOpenAI } from 'openai';
import type { ChatCompletion } from 'openai/resources/chat/completions';

// ─── Types ──────────────────────────────────────────────────────────────────

export interface WidgetState {
  provider: string;
  timestamp: number;
  messages: Array<{
    sender: 'user' | 'agent' | 'system' | 'unknown';
    text: string;
    timestamp?: string | null;
  }>;
  buttons: Array<{
    label: string;
    type: string;
    selector?: string;
  }>;
  inputField: {
    found: boolean;
    value: string;
    placeholder: string;
  };
  typingIndicator: boolean;
  url: string;
}

export interface CaseContext {
  caseId: string;
  customerName: string;
  issue: string;
  desiredOutcome: string;
  orderId?: string;
  hasAttachments: boolean;
  previousActions: string[];
}

export type AgentAction =
  | { type: 'type_message'; text: string }
  | { type: 'click_button'; buttonLabel: string; selector?: string }
  | { type: 'upload_file'; fileDescription: string }
  | { type: 'wait'; durationMs: number; reason: string }
  | { type: 'request_human_review'; reason: string; needsInput?: boolean; inputPrompt?: string }
  | { type: 'mark_resolved'; summary: string };

export interface AgentDecision {
  action: AgentAction;
  reasoning: string;
}

// ─── Tool definitions (OpenAI function-calling format) ──────────────────────

const TOOLS: Array<{
  type: 'function';
  function: { name: string; description: string; parameters: Record<string, unknown> };
}> = [
  {
    type: 'function',
    function: {
      name: 'type_message',
      description:
        'Type a message in the chat widget input field and send it to the support agent. ' +
        'Use this to describe the issue, answer questions, provide order details, or negotiate resolution.',
      parameters: {
        type: 'object',
        properties: {
          text: { type: 'string', description: 'The message to type and send' },
        },
        required: ['text'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'click_button',
      description:
        'Click a button or quick-reply option visible in the chat widget. ' +
        'Use this to select menu options, confirm choices, or navigate the support flow.',
      parameters: {
        type: 'object',
        properties: {
          buttonLabel: { type: 'string', description: 'The exact label text of the button to click' },
        },
        required: ['buttonLabel'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'upload_file',
      description:
        'Upload evidence file (screenshot, receipt, photo) to the support chat. ' +
        'Use this when the support agent asks for proof or when submitting evidence strengthens the case.',
      parameters: {
        type: 'object',
        properties: {
          fileDescription: {
            type: 'string',
            description: 'Description of which attached file to upload (e.g., "damaged package photo")',
          },
        },
        required: ['fileDescription'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'wait_for_response',
      description:
        'Wait for the support agent or bot to respond before taking the next action. ' +
        'Use this after sending a message when you expect a reply.',
      parameters: {
        type: 'object',
        properties: {
          reason: {
            type: 'string',
            description: 'Why we are waiting (e.g., "waiting for agent to respond to refund request")',
          },
        },
        required: ['reason'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'request_human_review',
      description:
        'Pause the automation and ask the actual customer (the human using Resolve) for input. ' +
        'Use this when: (1) the support agent asks for information you do not have, ' +
        '(2) a sensitive decision needs human approval, (3) you are unsure how to proceed.',
      parameters: {
        type: 'object',
        properties: {
          reason: { type: 'string', description: 'Why the customer needs to review or provide input' },
          needsInput: { type: 'boolean', description: 'Whether the customer needs to type a response' },
          inputPrompt: { type: 'string', description: 'What to ask the customer for (shown in the UI)' },
        },
        required: ['reason'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'mark_resolved',
      description:
        'Mark the support case as resolved. Use this when: (1) the desired outcome has been achieved, ' +
        '(2) the support agent has confirmed the resolution, or (3) the ticket has been closed.',
      parameters: {
        type: 'object',
        properties: {
          summary: { type: 'string', description: 'Summary of what was resolved and the outcome' },
        },
        required: ['summary'],
      },
    },
  },
];

// ─── System prompt ──────────────────────────────────────────────────────────

function buildSystemPrompt(context: CaseContext): string {
  return `You are Resolve, an AI agent acting on behalf of a customer to resolve their support issue. You are operating inside a customer support chat widget on a company's website.

## Your Role
You are the customer's advocate. The customer has described their issue and desired outcome to you, and you are now handling the support conversation for them. You speak AS the customer (first person), not as a third party.

## Customer's Issue
${context.issue}

## Desired Outcome
${context.desiredOutcome}

${context.orderId ? `## Order ID\n${context.orderId}\n` : ''}
${context.hasAttachments ? '## Evidence\nThe customer has provided attachment(s) as evidence. Upload them when relevant.\n' : ''}

## Rules
1. Be polite but assertive. You represent the customer's interests.
2. NEVER reveal that you are an AI or automation tool. Speak as the customer.
3. NEVER share sensitive personal information (SSN, full credit card number, passwords) - if asked, request_human_review.
4. If the support agent asks for information you don't have, use request_human_review to ask the customer.
5. Stay focused on resolving the specific issue. Don't get sidetracked.
6. If presented with options, choose the one closest to the desired outcome.
7. If the support bot offers a resolution matching the desired outcome, accept it.
8. If the conversation seems stuck or going in circles, try a different approach.
9. Upload evidence proactively when it strengthens the case.
10. After sending a message, always wait_for_response before taking the next action.
11. Mark the case as resolved only when you have confirmation from the support side.

## Available Actions
You can: type messages, click buttons, upload files, wait for responses, request human review, or mark the case resolved.

## Current State
You will receive the current state of the chat widget, including visible messages, available buttons, and the input field state. Use this information to decide your next action.

Analyze the situation carefully and take exactly one action at a time.`;
}

// ─── Client ─────────────────────────────────────────────────────────────────

export class ClaudeClient {
  private readonly client: AzureOpenAI;
  private readonly deployment: string;

  constructor() {
    const apiKey = process.env.AZURE_OPENAI_API_KEY;
    const endpoint = process.env.AZURE_OPENAI_ENDPOINT;

    if (!apiKey || !endpoint) {
      throw new Error(
        'AZURE_OPENAI_API_KEY and AZURE_OPENAI_ENDPOINT environment variables are required',
      );
    }

    this.deployment = process.env.AZURE_OPENAI_DEPLOYMENT ?? 'gpt-5-nano';
    this.client = new AzureOpenAI({
      apiKey,
      endpoint,
      apiVersion: process.env.AZURE_OPENAI_API_VERSION ?? '2024-10-21',
    });
  }

  /**
   * Given the current widget state and case context, decide the next action.
   */
  async decideNextAction(
    widgetState: WidgetState,
    context: CaseContext,
  ): Promise<AgentDecision> {
    const systemPrompt = buildSystemPrompt(context);
    const userMessage = this.formatWidgetState(widgetState, context);

    const response: ChatCompletion = await this.client.chat.completions.create({
      model: this.deployment,
      max_completion_tokens: 1024,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userMessage },
      ],
      tools: TOOLS,
      tool_choice: 'required',
    });

    return this.parseResponse(response);
  }

  /**
   * Format the widget state into a clear message for the LLM.
   */
  private formatWidgetState(state: WidgetState, context: CaseContext): string {
    const parts: string[] = [];

    parts.push(`## Widget: ${state.provider} on ${state.url}`);
    parts.push('');

    // Chat messages
    if (state.messages.length > 0) {
      parts.push('## Chat Messages');
      for (const msg of state.messages) {
        const label =
          msg.sender === 'user' ? 'You (customer)' :
          msg.sender === 'agent' ? 'Support' :
          msg.sender === 'system' ? 'System' : 'Unknown';
        parts.push(`[${label}]: ${msg.text}`);
      }
      parts.push('');
    } else {
      parts.push('## Chat Messages');
      parts.push('No messages yet. The chat appears to be empty or just started.');
      parts.push('');
    }

    // Available buttons
    if (state.buttons.length > 0) {
      parts.push('## Available Buttons');
      for (const btn of state.buttons) {
        parts.push(`- "${btn.label}" (${btn.type})`);
      }
      parts.push('');
    }

    // Input field
    parts.push('## Input Field');
    if (state.inputField.found) {
      parts.push(`Available: yes | Placeholder: "${state.inputField.placeholder}"`);
      if (state.inputField.value) {
        parts.push(`Current value: "${state.inputField.value}"`);
      }
    } else {
      parts.push('No input field visible.');
    }
    parts.push('');

    // Typing indicator
    if (state.typingIndicator) {
      parts.push('## Status: Support agent is typing...');
      parts.push('');
    }

    // Previous actions
    if (context.previousActions.length > 0) {
      parts.push('## Your Previous Actions');
      for (const action of context.previousActions.slice(-8)) {
        parts.push(`- ${action}`);
      }
      parts.push('');
    }

    parts.push('What is your next action? Choose exactly one tool to use.');

    return parts.join('\n');
  }

  /**
   * Parse the OpenAI response into an AgentDecision.
   */
  private parseResponse(response: ChatCompletion): AgentDecision {
    const choice = response.choices?.[0];
    if (!choice) {
      return {
        action: { type: 'wait', durationMs: 3000, reason: 'No response from LLM' },
        reasoning: 'No response',
      };
    }

    const message = choice.message;
    const reasoning = message.content ?? '';

    // Check for tool calls
    const toolCalls = message.tool_calls;
    if (toolCalls && toolCalls.length > 0) {
      const tc = toolCalls[0];
      if (tc.type === 'function') {
        const action = this.toolCallToAction(tc.function.name, tc.function.arguments);
        return { action, reasoning };
      }
    }

    // No tool call - default to waiting
    return {
      action: { type: 'wait', durationMs: 3000, reason: reasoning || 'Thinking about next step' },
      reasoning,
    };
  }

  /**
   * Convert a function call to an AgentAction.
   */
  private toolCallToAction(name: string, argsJson: string): AgentAction {
    let input: Record<string, string | boolean | number>;
    try {
      input = JSON.parse(argsJson);
    } catch {
      return { type: 'wait', durationMs: 3000, reason: `Failed to parse tool args: ${argsJson}` };
    }

    switch (name) {
      case 'type_message':
        return { type: 'type_message', text: String(input.text) };

      case 'click_button':
        return { type: 'click_button', buttonLabel: String(input.buttonLabel) };

      case 'upload_file':
        return { type: 'upload_file', fileDescription: String(input.fileDescription) };

      case 'wait_for_response':
        return { type: 'wait', durationMs: 5000, reason: String(input.reason) };

      case 'request_human_review':
        return {
          type: 'request_human_review',
          reason: String(input.reason),
          needsInput: input.needsInput === true,
          inputPrompt: input.inputPrompt ? String(input.inputPrompt) : undefined,
        };

      case 'mark_resolved':
        return { type: 'mark_resolved', summary: String(input.summary) };

      default:
        return { type: 'wait', durationMs: 3000, reason: `Unknown tool: ${name}` };
    }
  }
}
