import test from 'node:test';
import assert from 'node:assert/strict';
import { validate, extractJsonStrict, normalizeMeal, mealTotals, mealTotalsConsistent, MEAL_SCHEMA, ORGANIZE_SCHEMA, SLEEP_EVENTS_SCHEMA } from './ai-schemas.mjs';

test('extractJsonStrict: text curat, ```json, text în jur, listă; nimic → null', () => {
  assert.deepEqual(extractJsonStrict('{"a":1}'), { a: 1 });
  assert.deepEqual(extractJsonStrict('```json\n{"a":"}"}\n```'), { a: '}' });
  assert.deepEqual(extractJsonStrict('Sigur! {"a":{"b":[1,2]}} gata.'), { a: { b: [1, 2] } });
  assert.deepEqual(extractJsonStrict('[1,2]'), [1, 2]);
  assert.equal(extractJsonStrict('nimic aici'), null);
  assert.equal(extractJsonStrict('{"a": '), null);
  assert.equal(extractJsonStrict(''), null);
});

test('validate: tipuri, enum, required, limite, nullable, erori cu cale', () => {
  const good = { fel: 'Ciorbă', incredere: 'medie', componente: [{ nume: 'ciorbă', grame: 400, kcal: 180, proteine: 8, carbo: 14, grasimi: 9 }] };
  assert.equal(validate(MEAL_SCHEMA, good).ok, true);
  const bad = validate(MEAL_SCHEMA, { fel: 3, incredere: 'sigur', componente: [{ nume: 'x', grame: -1 }] });
  assert.equal(bad.ok, false);
  assert.ok(bad.errors.some((e) => e.startsWith('$.fel:')));
  assert.ok(bad.errors.some((e) => e.includes('$.componente[0]') && e.includes('kcal')));
  assert.ok(bad.errors.some((e) => e.includes('$.componente[0].grame') && e.includes('minim')));
  assert.equal(validate(ORGANIZE_SCHEMA, { items: [{ id: 'a', suggestion: 'keep', folder: null, duplicatDe: null }] }).ok, true);
  assert.equal(validate(ORGANIZE_SCHEMA, { items: [{ id: 'a', suggestion: 'archive' }] }).ok, false);
  assert.equal(validate(SLEEP_EVENTS_SCHEMA, { events: [] }).ok, true);
  assert.equal(validate(SLEEP_EVENTS_SCHEMA, { events: [{ type: 'talk', startMs: 5, endMs: 8, transcript: 'x' }] }).ok, true);
  assert.equal(validate(SLEEP_EVENTS_SCHEMA, { events: [{ type: 'talk', startMs: 'x', endMs: 8 }] }).ok, false);
  assert.equal(validate(SLEEP_EVENTS_SCHEMA, { events: [{ type: 'silence', startMs: 1, endMs: 2 }] }).ok, false);
});

test('mese v2: totalurile se recalculează din componente, verificarea 4P+4C+9G ±15 %, versiune 2, câmpuri vechi intacte', () => {
  const parsed = {
    fel: ' Pui cu orez ', incredere: 'ridicată',
    componente: [
      { nume: 'piept de pui', grame: 150.4, kcal: 248, proteine: 46, carbo: 0, grasimi: 6, fibre: 0, incredere: 'ridicată' },
      { nume: 'orez', grame: 180, kcal: 234, proteine: 5, carbo: 50, grasimi: 0.5, fibre: 1 },
    ],
    total: { kcal: 9999, proteine: 1, carbo: 1, grasimi: 1, fibre: 1 },
    scor: { valoare: 7.4, motiv: 'Echilibrată.' }, sfat: 'Adaugă legume.', observatii: ['ulei invizibil', ''], portie: 'farfurie medie',
  };
  const m = normalizeMeal(parsed, 'gemini-2.5-flash');
  assert.equal(m.versiune, 2);
  assert.equal(m.model, 'gemini-2.5-flash');
  assert.equal(m.fel, 'Pui cu orez');
  assert.deepEqual(m.total, { kcal: 482, proteine: 51, carbo: 50, grasimi: 7, fibre: 1 });
  assert.equal(mealTotalsConsistent(m.total), true);
  assert.equal(m.scor.valoare, 7);
  assert.deepEqual(m.observatii, ['ulei invizibil']);
  assert.equal(m.componente[1].incredere, 'ridicată', 'moștenește încrederea felului');
  assert.deepEqual(Object.keys(m.componente[0]), ['nume', 'grame', 'kcal', 'proteine', 'carbo', 'grasimi', 'fibre', 'incredere']);
  assert.equal(m.componente[0].grame, 150);
  assert.ok(['fel', 'incredere', 'componente'].every((k) => k in m), 'contractul vechi rămâne');

  // Totaluri contradictorii (kcal mult peste macro) → încrederea coboară de la ridicată la medie.
  const off = normalizeMeal({ fel: 'x', incredere: 'ridicată', componente: [{ nume: 'a', grame: 100, kcal: 900, proteine: 10, carbo: 10, grasimi: 10 }] });
  assert.equal(mealTotalsConsistent(off.total), false);
  assert.equal(off.incredere, 'medie');

  // Fără mâncare: contract identic cu cel vechi + câmpurile noi goale.
  const none = normalizeMeal({ fel: '', incredere: 'scăzută', componente: [] });
  assert.equal(none.fel, '');
  assert.equal(none.incredere, 'scăzută');
  assert.deepEqual(none.componente, []);
  assert.deepEqual(none.total, { kcal: 0, proteine: 0, carbo: 0, grasimi: 0, fibre: 0 });
  assert.equal(none.scor.valoare, 1);
  assert.deepEqual(mealTotals(undefined), { kcal: 0, proteine: 0, carbo: 0, grasimi: 0, fibre: 0 });
});
