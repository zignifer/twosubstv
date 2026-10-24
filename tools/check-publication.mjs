// Checks only tracked public files. Never reads local credentials or device profiles.
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';

const files = execFileSync('git', ['ls-files', '-z'], {encoding: 'utf8'}).split('\0').filter(Boolean);
const forbidden = /(^|\/)(private|\.local-signing|node_modules|build|\.gradle|\.playwright-cli|captures|local-test)(\/|$)|\.(?:apk|aab|p12|pfx|jks|keystore|pyc|hprof|log)$/i;
for (const file of files) assert(!forbidden.test(file), `Private/generated file tracked: ${file}`);
for (const file of files.filter(f => f.endsWith('.md'))) {
  const source = fs.readFileSync(file, 'utf8').replace(/```[\s\S]*?```/g, '');
  const links = [...source.matchAll(/\]\(([^)\s]+)(?:\s+"[^"]*")?\)|(?:src|href)="([^"]+)"/g)];
  for (const match of links) {
    const target = match[1] ?? match[2];
    if (/^(https?:|mailto:|#|data:)/.test(target)) continue;
    const pathname = decodeURIComponent(target.split('#')[0]);
    const local = path.normalize(path.join(path.dirname(file), pathname));
    assert(fs.existsSync(local), `Broken relative link in ${file}: ${target}`);
    assert(files.includes(local.split(path.sep).join('/')), `Linked file is not tracked: ${local}`);
  }
}
const manifest = JSON.parse(fs.readFileSync('release.json', 'utf8'));
assert.equal(manifest.packageId, 'app.twosub.tv');
assert.equal(manifest.minSdk, 26);
assert.equal(manifest.debuggable, false);
assert.match(manifest.sha256, /^[a-f0-9]{64}$/);
assert.match(manifest.signingCertificateSha256, /^[a-f0-9]{64}$/);
assert.equal(manifest.downloadUrl, `https://github.com/zignifer/twosubstv/releases/download/v${manifest.version}/2sub-tv.apk`);
const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');
assert(gradle.includes(`versionName = "${manifest.version}"`));
assert(gradle.includes(`versionCode = ${manifest.versionCode}`));
assert.equal(fs.readFileSync('SHA256SUMS.txt', 'utf8').trim(), `${manifest.sha256}  2sub-tv.apk`);
for (const file of files.filter(f => /\.(?:md|json|ya?ml|kt|js|cjs|mjs|ps1|py|xml|properties)$/.test(f))) {
  const text = fs.readFileSync(file, 'utf8');
  assert(!/C:[\\/]Users[\\/](?:КОМП|01EC)/i.test(text), `Personal path in ${file}`);
  assert(!/192\.168\.0\.104/.test(text), `Private device address in ${file}`);
  assert(!/(?:gh[opusr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----)/.test(text), `Possible secret in ${file}`);
}
console.log(`PASS: ${files.length} public files; documentation links, release metadata and private-file exclusions.`);
