import test from 'node:test';
import assert from 'node:assert/strict';
import {validateDeletionReview} from './organizer-review.mjs';
const evidence=[{id:'e1',kind:'visual',observation:'Imaginea pare uniformă, fără text lizibil.',interpretation:'unconfirmed_ai_observation'}];
const review={suggested:true,basis:'low_information',reason:'Imaginea pare aproape goală. Verifică dacă mai ai nevoie de ea.',evidence_ids:['e1']};
test('a grounded low-information interpretation creates only a review label',()=>{
  const result=validateDeletionReview(review,evidence);assert.equal(result.suggested,true);assert.equal(result.requires_confirmation,true);assert.equal(result.review_only,true);assert.deepEqual(result.evidence_ids,['e1']);
  assert.equal(Object.hasOwn(result,'delete'),false);assert.equal(Object.hasOwn(result,'selected'),false);
});
test('protected files suppress model deletion review without removing protection',()=>{
  const result=validateDeletionReview(review,evidence,{protectedFile:true});assert.equal(result.suggested,false);assert.equal(result.reason,'Fișier protejat.');
});
test('invented evidence, unsupported rationale, duplicate claims and executable fields fail closed',()=>{
  for(const patch of [{evidence_ids:['e6']},{evidence_ids:[]},{evidence_ids:['e1','e1']},{basis:'old_file'},{basis:'duplicate'},{reason:''},{delete:true},{requires_confirmation:false},{reason:'Line\ncommand'}])assert.throws(()=>validateDeletionReview({...review,...patch},evidence));
});
test('no review cannot carry a contradictory selection or evidence',()=>{
  assert.equal(validateDeletionReview({suggested:false,basis:'none',reason:'',evidence_ids:[]},evidence).suggested,false);
  assert.throws(()=>validateDeletionReview({...review,suggested:false},evidence));
});
