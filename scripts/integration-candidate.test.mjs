import test from "node:test";
import assert from "node:assert/strict";
import { inspectIntegrationCandidate } from "./integration-candidate.mjs";

const REPO="nafisajuliansahsaputra/smart-cafe-system";
const HASH="a".repeat(40);
function pr(n,head,base,sha,opts={}) {
  return {
    number:n,draft:opts.draft??true,
    head:{ref:head,sha,repo:{full_name:opts.from??REPO}},
    base:{ref:base,repo:{full_name:opts.to??REPO}},
  };
}
function good() {
  return [
    pr(61,"integration/staging-candidate","main",HASH),
    pr(60,"test/m6-last","feat/m6-prev",HASH),
    pr(1,"feat/m1-root","main","1".repeat(40)),
    pr(59,"feat/m6-prev","feat/m1-root","2".repeat(40)),
  ];
}
test("direct-to-main candidate must match a unique full stacked source",()=>{
  const result=inspectIntegrationCandidate(good(),"integration/staging-candidate",REPO);
  assert.equal(result.candidateNumber,61);
  assert.equal(result.sourceNumber,60);
  assert.equal(result.chain.length,3);
  assert.deepEqual(result.chain.map(x=>x.number),[1,59,60]);
});
test("candidate cannot diverge even by one commit",()=>{
  const all=good();
  all[0].head.sha="b".repeat(40);
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /exactly match one/);
});
test("candidate must be draft and target main",()=>{
  const all=good();
  all[0].draft=false;
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /draft PR/);
  all[0].draft=true;
  all[0].base.ref="feat/m1-root";
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /directly into main/);
});
test("forked candidate cannot impersonate source repository",()=>{
  const all=good();
  all[0].head.repo.full_name="untrusted/fork";
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /exactly one same-repo/);
});
test("missing or duplicate source SHA fails closed",()=>{
  const all=good();
  all.splice(1,1);
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /exactly match one/);
  all.splice(1,0,pr(60,"test/m6-last","feat/m6-prev",HASH));
  all.push(pr(62,"feat/other","feat/m6-prev",HASH));
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /exactly match one/);
});
test("broken source dependency chain fails closed",()=>{
  const all=good();
  all[1].base.ref="missing-parent";
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /Missing same-repo/);
});
test("non-draft dependent parent cannot silently pass",()=>{
  const all=good();
  all[2].draft=false;
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /non-draft parent/);
});
test("invalid branch name or commit SHA is rejected",()=>{
  assert.throws(()=>inspectIntegrationCandidate(good(),"feat/m6-last",REPO),
    /integration/);
  const all=good();
  all[0].head.sha="garbage";
  assert.throws(()=>inspectIntegrationCandidate(all,"integration/staging-candidate",REPO),
    /malformed/);
});
