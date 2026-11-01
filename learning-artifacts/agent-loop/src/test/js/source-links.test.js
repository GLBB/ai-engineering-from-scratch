const test = require('node:test');
const assert = require('node:assert/strict');
const links = require('../../main/resources/static/source-links.js');
test('source links reject script URLs, credentials, missing labels and malformed URLs', () => {
  const urls = ['javascript:alert(1)', 'http://example.test/', 'https://user:pass@example.test/', 'invalid'];
  assert.deepEqual(links.accept(urls.map(url => ({ label: 'source', url })), new Set()), []);
  assert.deepEqual(links.accept([{ label: '', url: 'https://example.test' }], new Set()), []);
});
test('sources are deduplicated across evidence events in one answer', () => {
  const seen = new Set();
  const one = [{ label: 'Open-Meteo', url: 'https://open-meteo.com' }, { label: 'GeoNames', url: 'https://www.geonames.org/' }];
  assert.equal(links.accept(one, seen).length, 2);
  assert.deepEqual(links.accept(one, seen), []);
  assert.equal(links.accept(one, new Set()).length, 2);
});
