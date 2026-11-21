#!/usr/bin/env node
/**
 * @fileoverview Personal Repository Zero-Leak Security & Hygiene Guardrail.
 * Ensures this 100% personal project never contains corporate domains, internal shortlinks,
 * workstation paths, internal tooling references, or corporate git author identities.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const PROJECT_ROOT = path.resolve(__dirname, '..');

// Construct forbidden patterns dynamically so the scanner file itself never contains literal forbidden tokens
const FORBIDDEN_RULES = [
  {
    id: 'CORP_EMAIL_OR_DOMAIN',
    description: 'Corporate email address, domain, or internal host',
    regex: new RegExp('\\b[a-z0-9._%+-]+@(goo' + 'gle|goo' + 'glers)\\.com\\b|\\b(goo' + 'glers|corp\\.goo' + 'gle)\\.com\\b', 'i'),
  },
  {
    id: 'INTERNAL_SHORTLINK',
    description: 'Internal corporate shortlink (go/, b/, cl/, xid/, yaqs/)',
    regex: new RegExp('(^|[\\s(\[{"\'])' + '(go|b|cl|xid|yaqs|critique)/[a-z0-9_-]{3,}', 'i'),
  },
  {
    id: 'INTERNAL_MONOREPO_OR_WORKSTATION_PATH',
    description: 'Internal monorepo or workstation filesystem path',
    regex: new RegExp('/goo' + 'gle/(src|data)|/usr/local/goo' + 'gle|\\b(goo' + 'gle3|citc|piper|blaze-bin|blaze-out|blaze-testlogs|srcfs)\\b', 'i'),
  },
  {
    id: 'INTERNAL_TOOLING_IDENTIFIERS',
    description: 'Internal corporate tool or infrastructure name',
    regex: new RegExp('\\b(stubby|fileutil|moma|g3doc|buganizer|yaqs|x20)\\b', 'i'),
  },
  {
    id: 'WORKSTATION_USER_OR_HOST',
    description: 'Workstation username or hostname leak',
    regex: new RegExp('\\b(sourou' + 'wilfried|wilfried\\.c\\.goo' + 'glers)\\b', 'i'),
  },
];

const IGNORED_DIRS = new Set(['node_modules', '.git', '.gradle', 'build']);
const BINARY_EXTENSIONS = new Set([
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.ico',
  '.woff',
  '.woff2',
  '.ttf',
  '.eot',
  '.jar',
  '.apk',
  '.aab',
  '.dex',
  '.so',
  '.litertlm',
  '.task',
  '.bin',
]);

/**
 * Recursively collects non-binary repository files outside ignored build/VCS directories.
 *
 * @param {string} dir Directory path to scan.
 * @param {string[]} [out=[]] Accumulator array for discovered file paths.
 * @return {string[]} List of absolute file paths to inspect.
 */
function collectRepoFiles(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    // Skip ignored build/VCS folders and macOS Finder metadata binaries (.DS_Store has empty extname).
    if (IGNORED_DIRS.has(name) || name === '.DS_Store') continue;
    const fullPath = path.join(dir, name);
    let stat;
    try {
      stat = fs.statSync(fullPath);
    } catch {
      continue;
    }
    if (stat.isDirectory()) {
      collectRepoFiles(fullPath, out);
    } else if (stat.isFile()) {
      const ext = path.extname(name).toLowerCase();
      if (!BINARY_EXTENSIONS.has(ext)) {
        out.push(fullPath);
      }
    }
  }
  return out;
}

/**
 * Audits all non-binary repository files and local Git configuration for corporate/internal leaks.
 *
 * @return {{scannedFileCount: number, violations: Array<Object>}} Scan summary and detected violations.
 */
function runPersonalRepoAudit() {
  const violations = [];
  const files = collectRepoFiles(PROJECT_ROOT);

  for (const filePath of files) {
    const relPath = path.relative(PROJECT_ROOT, filePath);
    // Skip scanning this guardrail script itself
    if (relPath === path.join('scripts', 'verify_clean_personal_repo.cjs')) continue;

    const content = fs.readFileSync(filePath, 'utf8');
    const lines = content.split('\n');

    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      for (const rule of FORBIDDEN_RULES) {
        const match = line.match(rule.regex);
        if (match) {
          violations.push({
            file: relPath,
            line: i + 1,
            ruleId: rule.id,
            description: rule.description,
            snippet: match[0].trim(),
          });
        }
      }
    }
  }

  // Also verify .git config author email and remotes if .git is initialized
  const gitDir = path.join(PROJECT_ROOT, '.git');
  if (fs.existsSync(gitDir)) {
    const emailRes = spawnSync('git', ['config', 'user.email'], { cwd: PROJECT_ROOT, encoding: 'utf8' });
    const email = (emailRes.stdout || '').trim();
    if (email && FORBIDDEN_RULES[0].regex.test(email)) {
      violations.push({
        file: '.git/config (user.email)',
        line: 1,
        ruleId: 'CORP_GIT_AUTHOR_EMAIL',
        description: 'Git user.email is set to a corporate email address; use your personal email for this repo',
        snippet: email,
      });
    }

    const remoteRes = spawnSync('git', ['remote', '-v'], { cwd: PROJECT_ROOT, encoding: 'utf8' });
    const remotes = (remoteRes.stdout || '').trim();
    if (remotes && (FORBIDDEN_RULES[0].regex.test(remotes) || FORBIDDEN_RULES[2].regex.test(remotes))) {
      violations.push({
        file: '.git/config (remotes)',
        line: 1,
        ruleId: 'CORP_GIT_REMOTE',
        description: 'Git remote points to a corporate host; only personal remotes (e.g. GitHub/GitLab) are allowed',
        snippet: remotes.split('\n')[0],
      });
    }
  }

  return { scannedFileCount: files.length, violations };
}

if (require.main === module) {
  const { scannedFileCount, violations } = runPersonalRepoAudit();
  if (violations.length > 0) {
    console.error('❌ [PERSONAL REPO GUARDRAIL FAILED] Found ' + violations.length + ' forbidden internal/corporate reference(s):');
    for (const v of violations) {
      console.error('  - [' + v.ruleId + '] ' + v.file + ':' + v.line + ' (' + v.description + ') -> "' + v.snippet + '"');
    }
    process.exit(1);
  }
  console.log('🔒 [PERSONAL REPO GUARDRAIL PASS] Scanned ' + scannedFileCount + ' files: 0 corporate/internal leaks detected.');
}

module.exports = { runPersonalRepoAudit };
