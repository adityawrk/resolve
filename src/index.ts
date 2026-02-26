import http from 'node:http';
import path from 'node:path';
import express from 'express';
import cors from 'cors';
import { z } from 'zod';
import { WebSocketServer, type WebSocket } from 'ws';
import { ApiKeySessionStore } from './domain/api-key-session-store.js';
import { CaseStore } from './domain/case-store.js';
import { DeviceStore } from './domain/device-store.js';
import { DeviceCommandStore } from './domain/device-command-store.js';
import { SupportAgentService } from './services/support-agent.js';
import { AgentLoopManager } from './services/agent-loop.js';
import { ConversationArchive } from './services/conversation-archive.js';
import { renderMockSupportPage } from './web/mock-support-page.js';

const app = express();
app.use(cors());
app.use(express.json({ limit: '15mb' }));

const port = Number(process.env.PORT ?? 8787);
const baseUrl = process.env.BASE_URL ?? `http://127.0.0.1:${port}`;
const mobileCommandTimeoutMs = Number(process.env.MOBILE_COMMAND_TIMEOUT_MS ?? 120000);
const conversationArchiveDir =
  process.env.CONVERSATION_ARCHIVE_DIR ?? path.resolve(process.cwd(), 'conversations');

const conversationArchive = new ConversationArchive(conversationArchiveDir);
const store = new CaseStore({
  onCaseChanged: (supportCase) => {
    conversationArchive.archiveCase(supportCase);
  },
});
const deviceStore = new DeviceStore();
const apiKeySessionStore = new ApiKeySessionStore();
const commandStore = new DeviceCommandStore();
const agent = new SupportAgentService({
  store,
  baseUrl,
  deviceStore,
  commandStore,
  mobileTimeoutMs: mobileCommandTimeoutMs,
});
const agentLoop = new AgentLoopManager({ store });

const createCaseSchema = z.object({
  customerName: z.string().min(2),
  issue: z.string().min(5),
  orderId: z.string().optional(),
  attachmentPaths: z.array(z.string()).optional(),
  desiredOutcome: z.string().optional(),
  files: z.array(z.object({
    name: z.string(),
    type: z.string(),
    data: z.string(), // base64
  })).optional(),
});

const startExtensionSchema = z.object({
  widgetProvider: z.string().min(1),
  tabId: z.number().optional(),
});

const startMobileSchema = z.object({
  deviceId: z.string().min(2),
  targetPlatform: z.string().min(2),
});

const appPermissionGrantSchema = z.object({
  appId: z.string().min(1),
  canLaunch: z.boolean(),
  canNavigateSupport: z.boolean(),
  canUploadMedia: z.boolean(),
});

const registerDeviceSchema = z.object({
  name: z.string().min(2),
  platform: z.enum(['android', 'ios']),
});

const updatePermissionsSchema = z.object({
  appGrants: z.array(appPermissionGrantSchema).min(1),
});

const devicePollSchema = z.object({
  deviceId: z.string().min(2),
});

const commandEventSchema = z.object({
  deviceId: z.string().min(2),
  message: z.string().min(1),
  stage: z.enum(['device_ack', 'step']),
  meta: z.record(z.union([z.string(), z.number(), z.boolean()])).optional(),
});

const commandCompleteSchema = z.object({
  deviceId: z.string().min(2),
  resultSummary: z.string().min(3),
});

const commandFailSchema = z.object({
  deviceId: z.string().min(2),
  errorMessage: z.string().min(3),
});

const clientMobileSubmitSchema = z.object({
  apiKey: z.string().min(8),
  appName: z.string().min(2),
  issue: z.string().min(3),
  description: z.string().optional(),
  orderId: z.string().optional(),
  customerName: z.string().min(2).optional(),
  attachmentPaths: z.array(z.string().min(1)).optional(),
  media: z.array(
    z.object({
      name: z.string().min(1),
      type: z.string().min(1),
      data: z.string().min(1),
    }),
  ).optional(),
  device: z.object({
    name: z.string().min(2).optional(),
    platform: z.enum(['android', 'ios']).optional(),
  }).optional(),
});

app.get('/health', (_req, res) => {
  res.json({ ok: true, timestamp: new Date().toISOString() });
});

app.get('/', (_req, res) => {
  res.type('text/plain').send(
    [
      'Customer Support Agent MVP',
      '',
      `Conversation archive dir: ${conversationArchiveDir}`,
      '',
      'Case Endpoints:',
      'POST /api/cases',
      'POST /api/cases/:id/start',
      'POST /api/cases/:id/start-mobile',
      'POST /api/cases/:id/start-extension',
      'POST /api/cases/:id/approve',
      'GET /api/cases',
      'GET /api/cases/:id',
      '',
      'Device Endpoints:',
      'POST /api/devices/register',
      'GET /api/devices',
      'GET /api/devices/:id',
      'POST /api/devices/:id/permissions',
      'GET /api/devices/:id/commands',
      '',
      'Companion Agent Endpoints:',
      'POST /api/device-agent/poll',
      'POST /api/device-agent/commands/:id/events',
      'POST /api/device-agent/commands/:id/complete',
      'POST /api/device-agent/commands/:id/fail',
      '',
      'Client Endpoint:',
      'POST /api/client/mobile/submit',
      '',
      'Demo UI:',
      'GET /mock-support',
    ].join('\n'),
  );
});

app.get('/mock-support', (_req, res) => {
  res.type('html').send(renderMockSupportPage());
});

app.post('/api/cases', (req, res) => {
  const parsed = createCaseSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const supportCase = store.create(parsed.data);
  return res.status(201).json(supportCase);
});

app.post('/api/client/mobile/submit', (req, res) => {
  const parsed = clientMobileSubmitSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const appId = normalizeApp(parsed.data.appName);
  const issueText = composeIssue(parsed.data.issue, parsed.data.description);
  const mediaAttachments = parsed.data.media?.map((file) => `media:${safeMediaName(file.name)}`) ?? [];
  const explicitAttachments = parsed.data.attachmentPaths ?? [];
  const attachmentPaths = uniqueStrings([...explicitAttachments, ...mediaAttachments]);
  const platform = parsed.data.device?.platform ?? 'android';
  const deviceName =
    parsed.data.device?.name?.trim() ||
    parsed.data.customerName?.trim() ||
    `Resolve ${platform.toUpperCase()} Companion`;

  const existingSession = apiKeySessionStore.get(parsed.data.apiKey);
  const existingDevice = existingSession ? deviceStore.get(existingSession.deviceId) : undefined;
  const device = existingDevice ?? deviceStore.register({ name: deviceName, platform });

  const customerName =
    parsed.data.customerName?.trim() ||
    existingSession?.customerName ||
    deriveCustomerNameFromApiKey(parsed.data.apiKey);

  const session = apiKeySessionStore.upsert(parsed.data.apiKey, {
    deviceId: device.id,
    deviceName: device.name,
    platform: device.platform,
    customerName,
  });

  deviceStore.upsertPermissionGrant(device.id, {
    appId,
    canLaunch: true,
    canNavigateSupport: true,
    canUploadMedia: true,
  });

  const supportCase = store.create({
    customerName,
    issue: issueText,
    orderId: parsed.data.orderId,
    attachmentPaths,
  });

  store.appendEvent(supportCase.id, {
    type: 'automation_step',
    message: 'Client mobile issue received via API key flow',
    meta: {
      appId,
      apiKeySession: session.id.slice(0, 12),
      mediaCount: mediaAttachments.length,
    },
  });

  agent.runCaseOnMobile(supportCase.id, {
    deviceId: device.id,
    targetPlatform: appId,
  });

  return res.status(202).json({
    status: 'accepted',
    caseId: supportCase.id,
    mode: 'mobile_companion',
    targetPlatform: appId,
    session: {
      id: session.id,
      deviceId: device.id,
      deviceToken: device.authToken,
      deviceName: device.name,
      platform: device.platform,
    },
    case: supportCase,
  });
});

app.get('/api/cases', (_req, res) => {
  res.json({ cases: store.list() });
});

app.get('/api/cases/:id', (req, res) => {
  const supportCase = store.get(req.params.id);
  if (!supportCase) {
    return res.status(404).json({ error: 'Case not found' });
  }

  return res.json(supportCase);
});

app.post('/api/cases/:id/start', (req, res) => {
  const supportCase = store.get(req.params.id);
  if (!supportCase) {
    return res.status(404).json({ error: 'Case not found' });
  }

  agent.runCase(supportCase.id);
  return res.json({ status: 'accepted', caseId: supportCase.id, mode: 'mock_browser' });
});

app.post('/api/cases/:id/start-mobile', (req, res) => {
  const supportCase = store.get(req.params.id);
  if (!supportCase) {
    return res.status(404).json({ error: 'Case not found' });
  }

  const parsed = startMobileSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const device = deviceStore.get(parsed.data.deviceId);
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  agent.runCaseOnMobile(supportCase.id, parsed.data);
  return res.json({
    status: 'accepted',
    caseId: supportCase.id,
    mode: 'mobile_companion',
    deviceId: parsed.data.deviceId,
    targetPlatform: parsed.data.targetPlatform,
  });
});

app.post('/api/cases/:id/start-extension', (req, res) => {
  const supportCase = store.get(req.params.id);
  if (!supportCase) {
    return res.status(404).json({ error: 'Case not found' });
  }

  const parsed = startExtensionSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  agent.runCaseOnExtension(supportCase.id, {
    widgetProvider: parsed.data.widgetProvider,
  });

  return res.json({
    status: 'accepted',
    caseId: supportCase.id,
    mode: 'browser_extension',
    widgetProvider: parsed.data.widgetProvider,
  });
});

app.post('/api/cases/:id/approve', (req, res) => {
  const supportCase = store.get(req.params.id);
  if (!supportCase) {
    return res.status(404).json({ error: 'Case not found' });
  }

  if (supportCase.status !== 'paused_for_approval') {
    return res.status(409).json({
      error: 'Case is not waiting for approval',
      status: supportCase.status,
    });
  }

  agent.approveAndRun(supportCase.id);
  return res.json({ status: 'accepted', caseId: supportCase.id });
});

app.post('/api/devices/register', (req, res) => {
  const parsed = registerDeviceSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const device = deviceStore.register(parsed.data);
  return res.status(201).json({
    device: deviceStore.publicView(device),
    authToken: device.authToken,
  });
});

app.get('/api/devices', (_req, res) => {
  return res.json({ devices: deviceStore.listPublic() });
});

app.get('/api/devices/:id', (req, res) => {
  const device = deviceStore.get(req.params.id);
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  return res.json({ device: deviceStore.publicView(device) });
});

app.post('/api/devices/:id/permissions', (req, res) => {
  const device = deviceStore.get(req.params.id);
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  const parsed = updatePermissionsSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const updatedDevice = deviceStore.updatePermissions(device.id, parsed.data);
  return res.json({ device: deviceStore.publicView(updatedDevice) });
});

app.get('/api/devices/:id/commands', (req, res) => {
  const device = deviceStore.get(req.params.id);
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  const commands = commandStore.listForDevice(device.id);
  return res.json({ commands });
});

app.post('/api/device-agent/poll', (req, res) => {
  const parsed = devicePollSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const token = req.header('x-device-token');
  if (!deviceStore.validateToken(parsed.data.deviceId, token)) {
    return res.status(401).json({ error: 'Unauthorized device token' });
  }

  deviceStore.touchHeartbeat(parsed.data.deviceId);

  const command = commandStore.pullNextQueued(parsed.data.deviceId);
  if (!command) {
    return res.json({ command: null });
  }

  store.appendEvent(command.payload.caseId, {
    type: 'automation_step',
    message: 'Companion device picked up queued command',
    meta: {
      deviceId: parsed.data.deviceId,
      commandId: command.id,
    },
  });

  return res.json({ command });
});

app.post('/api/device-agent/commands/:id/events', (req, res) => {
  const parsed = commandEventSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const token = req.header('x-device-token');
  if (!deviceStore.validateToken(parsed.data.deviceId, token)) {
    return res.status(401).json({ error: 'Unauthorized device token' });
  }

  const command = commandStore.get(req.params.id);
  if (!command) {
    return res.status(404).json({ error: 'Command not found' });
  }

  if (command.deviceId !== parsed.data.deviceId) {
    return res.status(403).json({ error: 'Command does not belong to this device' });
  }

  const updated = commandStore.appendEvent(command.id, {
    stage: parsed.data.stage,
    message: parsed.data.message,
    meta: parsed.data.meta,
  });

  store.appendEvent(command.payload.caseId, {
    type: 'automation_step',
    message: `[device:${parsed.data.deviceId}] ${parsed.data.message}`,
    meta: {
      commandId: command.id,
      stage: parsed.data.stage,
    },
  });

  return res.json({ command: updated });
});

app.post('/api/device-agent/commands/:id/complete', (req, res) => {
  const parsed = commandCompleteSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const token = req.header('x-device-token');
  if (!deviceStore.validateToken(parsed.data.deviceId, token)) {
    return res.status(401).json({ error: 'Unauthorized device token' });
  }

  const command = commandStore.get(req.params.id);
  if (!command) {
    return res.status(404).json({ error: 'Command not found' });
  }

  if (command.deviceId !== parsed.data.deviceId) {
    return res.status(403).json({ error: 'Command does not belong to this device' });
  }

  const completed = commandStore.complete(command.id, parsed.data.resultSummary);

  store.appendEvent(command.payload.caseId, {
    type: 'automation_step',
    message: 'Companion device marked command as complete',
    meta: {
      deviceId: parsed.data.deviceId,
      commandId: command.id,
    },
  });

  return res.json({ command: completed });
});

app.post('/api/device-agent/commands/:id/fail', (req, res) => {
  const parsed = commandFailSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({
      error: 'Invalid payload',
      details: parsed.error.flatten(),
    });
  }

  const token = req.header('x-device-token');
  if (!deviceStore.validateToken(parsed.data.deviceId, token)) {
    return res.status(401).json({ error: 'Unauthorized device token' });
  }

  const command = commandStore.get(req.params.id);
  if (!command) {
    return res.status(404).json({ error: 'Command not found' });
  }

  if (command.deviceId !== parsed.data.deviceId) {
    return res.status(403).json({ error: 'Command does not belong to this device' });
  }

  const failed = commandStore.fail(command.id, parsed.data.errorMessage);

  store.appendEvent(command.payload.caseId, {
    type: 'automation_step',
    message: 'Companion device marked command as failed',
    meta: {
      deviceId: parsed.data.deviceId,
      commandId: command.id,
    },
  });

  return res.json({ command: failed });
});

function composeIssue(issue: string, description: string | undefined): string {
  const issueLine = issue.trim();
  const details = description?.trim();
  if (!details) {
    return issueLine;
  }

  return `${issueLine}\n\nDetails: ${details}`;
}

function normalizeApp(appName: string): string {
  return appName.trim().toLowerCase();
}

function safeMediaName(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) {
    return 'attachment.bin';
  }

  return trimmed.replaceAll(/[^\w.\-]+/g, '_');
}

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}

function deriveCustomerNameFromApiKey(apiKey: string): string {
  const compact = apiKey.trim().replaceAll(/\s+/g, '');
  if (!compact) {
    return 'Resolve Customer';
  }

  const suffix = compact.slice(-6).toUpperCase();
  return `Resolve Customer ${suffix}`;
}

// ─── HTTP server + WebSocket ────────────────────────────────────────────────

const server = http.createServer(app);

const wss = new WebSocketServer({ server, path: '/ws/agent' });

/** Map of WebSocket connections to their active case ID. */
const wsClients = new Map<WebSocket, string | null>();

wss.on('connection', (ws: WebSocket) => {
  wsClients.set(ws, null);
  // eslint-disable-next-line no-console
  console.log('[WS] Extension connected');

  ws.on('message', (raw: Buffer) => {
    try {
      const msg = JSON.parse(raw.toString());
      handleWsMessage(ws, msg);
    } catch (err) {
      console.error('[WS] Bad message:', err);
    }
  });

  ws.on('close', () => {
    const caseId = wsClients.get(ws);
    if (caseId) {
      agentLoop.stopSession(caseId);
    }
    wsClients.delete(ws);
    // eslint-disable-next-line no-console
    console.log('[WS] Extension disconnected');
  });
});

function handleWsMessage(ws: WebSocket, msg: Record<string, unknown>): void {
  const type = msg.type as string;
  const caseId = msg.caseId as string | undefined;

  switch (type) {
    case 'widget_state': {
      if (!caseId) return;

      // If no session exists for this case, start one
      const supportCase = caseId ? store.get(caseId) : undefined;
      if (supportCase && supportCase.status === 'running') {
        // Check if session needs to be started
        wsClients.set(ws, caseId);

        // Start agent loop session if not already running
        agentLoop.startSession(caseId, ws, {
          caseId,
          customerName: supportCase.customerName,
          issue: supportCase.issue,
          desiredOutcome: supportCase.desiredOutcome ?? supportCase.strategy,
          orderId: supportCase.orderId,
          hasAttachments: supportCase.attachmentPaths.length > 0,
          previousActions: [],
        });
      }

      agentLoop.onWidgetState(caseId, msg.state as unknown as import('./services/claude-client.js').WidgetState);
      break;
    }

    case 'action_result': {
      if (caseId) {
        agentLoop.onActionResult(
          caseId,
          msg.actionId as string,
          msg.success as boolean,
          msg.error as string | undefined,
        );
      }
      break;
    }

    case 'pause_case': {
      if (caseId) agentLoop.pauseSession(caseId);
      break;
    }

    case 'stop_case': {
      if (caseId) agentLoop.stopSession(caseId);
      break;
    }

    case 'approve_case': {
      if (caseId) agentLoop.resumeSession(caseId, msg.userInput as string | undefined);
      break;
    }
  }
}

server.listen(port, () => {
  // eslint-disable-next-line no-console
  console.log(`CS support agent listening on ${baseUrl}`);
  console.log(`WebSocket agent endpoint: ws://127.0.0.1:${port}/ws/agent`);
});
