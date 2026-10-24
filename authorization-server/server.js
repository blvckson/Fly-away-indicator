'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8080);
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data.json');
const ADMIN_API_KEY = process.env.ADMIN_API_KEY || '';
const DAY = 24 * 60 * 60 * 1000;

function freshData() {
  return { defaultPeriodDays: 14, authorizations: [] };
}
function loadData() {
  try {
    const parsed = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    return { defaultPeriodDays: Number(parsed.defaultPeriodDays) || 14, authorizations: Array.isArray(parsed.authorizations) ? parsed.authorizations : [] };
  } catch (_) { return freshData(); }
}
let data = loadData();
function persist() {
  fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
  const temp = DATA_FILE + '.tmp';
  fs.writeFileSync(temp, JSON.stringify(data, null, 2), { mode: 0o600 });
  fs.renameSync(temp, DATA_FILE);
}
function hash(value) { return crypto.createHash('sha256').update(String(value)).digest('hex'); }
function nowIso(ms = Date.now()) { return new Date(ms).toISOString(); }
function safeStatus(record, save = true) {
  if (record.status === 'active' && record.expiresAt && Date.now() >= Date.parse(record.expiresAt)) {
    record.status = 'expired';
    if (save) persist();
  }
  return record.status;
}
function remainingSeconds(record) {
  if (safeStatus(record, false) !== 'active' || !record.expiresAt) return 0;
  return Math.max(0, Math.floor((Date.parse(record.expiresAt) - Date.now()) / 1000));
}
function publicRecord(record) {
  const status = safeStatus(record, false);
  return {
    id: record.id, name: record.name, status, issuedAt: record.issuedAt,
    activatedAt: record.activatedAt, expiresAt: record.expiresAt,
    durationDays: record.durationDays, installationId: record.installationId || '',
    codeState: record.codeHash ? 'unused' : 'consumed',
    remainingSeconds: remainingSeconds(record)
  };
}
function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(body);
}
function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', chunk => { raw += chunk; if (raw.length > 32768) { reject(new Error('Request too large')); req.destroy(); } });
    req.on('end', () => {
      try { resolve(raw ? JSON.parse(raw) : {}); } catch (_) { reject(new Error('Invalid JSON')); }
    });
    req.on('error', reject);
  });
}
function requireAdmin(body) {
  if (!ADMIN_API_KEY || typeof body.adminKey !== 'string') return false;
  const a = Buffer.from(body.adminKey);
  const b = Buffer.from(ADMIN_API_KEY);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function findById(id) { return data.authorizations.find(x => x.id === id); }
function fail(res, code, message) { send(res, code, { message }); }

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true });
  if (req.method !== 'POST') return fail(res, 405, 'Method not allowed.');
  let body;
  try { body = await readJson(req); } catch (e) { return fail(res, 400, e.message); }

  if (req.url === '/v1/customer/activate') {
    const name = String(body.name || '').trim();
    const code = String(body.code || '').trim().toUpperCase();
    const installationId = String(body.installationId || '').trim();
    if (!name || !code || !installationId) return fail(res, 400, 'Name, code, and installation are required.');
    const record = data.authorizations.find(x => x.codeHash && x.codeHash === hash(code) && x.status === 'pending');
    if (!record) return fail(res, 403, 'Code is invalid, already used, or revoked.');
    if (name.toLocaleLowerCase() !== record.name.toLocaleLowerCase()) return fail(res, 403, 'The name does not match the customer registered by Admin AI.');
    const now = Date.now();
    record.codeHash = null;
    record.installationId = installationId;
    record.activatedAt = nowIso(now);
    record.expiresAt = nowIso(now + record.durationDays * DAY);
    record.status = 'active';
    record.updatedAt = nowIso(now);
    persist();
    return send(res, 200, { authorized: true, customerName: record.name, activatedAt: record.activatedAt, expiresAt: record.expiresAt });
  }

  if (req.url === '/v1/customer/status') {
    const installationId = String(body.installationId || '').trim();
    if (!installationId) return fail(res, 400, 'Installation identifier is required.');
    const record = data.authorizations.filter(x => x.installationId === installationId).sort((a, b) => String(b.activatedAt || '').localeCompare(String(a.activatedAt || '')))[0];
    if (!record) return send(res, 200, { authorized: false, message: 'This installation has no authorization.' });
    const status = safeStatus(record);
    if (status !== 'active') return send(res, 200, { authorized: false, status, message: 'Authorization expired or deactivated.' });
    return send(res, 200, { authorized: true, status, expiresAt: record.expiresAt, remainingSeconds: remainingSeconds(record) });
  }

  if (!requireAdmin(body)) return fail(res, 401, 'Administrator credentials are invalid.');

  if (req.url === '/v1/admin/authorizations/create') {
    const name = String(body.name || '').trim();
    const days = Number(body.durationDays || data.defaultPeriodDays);
    if (!name || !Number.isInteger(days) || days < 1 || days > 3650) return fail(res, 400, 'Provide a customer name and a period between 1 and 3650 days.');
    const code = crypto.randomBytes(8).toString('hex').toUpperCase();
    const record = {
      id: crypto.randomUUID(), name, status: 'pending', issuedAt: nowIso(),
      durationDays: days, codeHash: hash(code), installationId: '',
      activatedAt: null, expiresAt: null, updatedAt: nowIso()
    };
    data.authorizations.push(record);
    persist();
    return send(res, 201, { id: record.id, name, code, durationDays: days, status: 'pending', message: 'Single-use code created. Its authorization period starts on activation.' });
  }

  if (req.url === '/v1/admin/authorizations/list') {
    data.authorizations.forEach(r => safeStatus(r, false));
    persist();
    const rows = data.authorizations.slice().sort((a, b) => String(b.issuedAt).localeCompare(String(a.issuedAt))).map(publicRecord);
    const counts = { total: rows.length, active: rows.filter(x => x.status === 'active').length, pending: rows.filter(x => x.status === 'pending').length, expired: rows.filter(x => x.status === 'expired').length, deactivated: rows.filter(x => x.status === 'deactivated').length };
    return send(res, 200, { defaultPeriodDays: data.defaultPeriodDays, counts, authorizations: rows });
  }

  if (req.url === '/v1/admin/settings/default-period') {
    const days = Number(body.durationDays);
    if (!Number.isInteger(days) || days < 1 || days > 3650) return fail(res, 400, 'Default period must be between 1 and 3650 days.');
    data.defaultPeriodDays = days; persist();
    return send(res, 200, { message: 'Default period updated to ' + days + ' days.', defaultPeriodDays: days });
  }

  if (req.url === '/v1/admin/authorizations/update') {
    const record = findById(String(body.id || ''));
    const action = String(body.action || '');
    const days = Number(body.durationDays || 0);
    if (!record) return fail(res, 404, 'Customer authorization not found.');
    const now = Date.now();
    safeStatus(record, false);
    if (action === 'deactivate') {
      record.status = 'deactivated'; record.updatedAt = nowIso(now); persist();
      return send(res, 200, { message: record.name + ' has been deactivated.' });
    }
    if (action === 'reactivate') {
      const period = Number.isInteger(days) && days > 0 ? days : data.defaultPeriodDays;
      record.activatedAt = nowIso(now);
      record.expiresAt = nowIso(now + period * DAY);
      record.durationDays = period;
      record.status = 'active';
      record.updatedAt = nowIso(now);
      persist();
      return send(res, 200, { message: record.name + ' has been reactivated for ' + period + ' days.' });
    }
    if (!['extend', 'shorten'].includes(action) || !Number.isInteger(days) || days < 1 || days > 3650) return fail(res, 400, 'Invalid authorization update.');
    if (!record.expiresAt) return fail(res, 409, 'This code has not been activated yet; use the default duration or issue a new code.');
    const oldExpiry = Date.parse(record.expiresAt);
    const base = oldExpiry > now ? oldExpiry : now;
    const newExpiry = action === 'extend' ? base + days * DAY : oldExpiry - days * DAY;
    record.expiresAt = nowIso(newExpiry);
    record.status = newExpiry > now ? 'active' : 'expired';
    record.durationDays = Math.max(1, Math.ceil((newExpiry - Date.parse(record.activatedAt || nowIso(now))) / DAY));
    record.updatedAt = nowIso(now);
    persist();
    return send(res, 200, { message: action === 'extend' ? record.name + ' extended from the existing expiry date.' : record.name + ' authorization period shortened.' });
  }

  return fail(res, 404, 'Unknown endpoint.');
});

server.listen(PORT, '0.0.0.0', () => {
  if (!ADMIN_API_KEY) console.error('WARNING: ADMIN_API_KEY is unset; admin endpoints will reject all requests.');
  console.log('Fly Away authorization service listening on ' + PORT);
});
