import test from "node:test";
import assert from "node:assert/strict";
import { inspectReleaseChain } from "./release-chain.mjs";
import { inspectSyntheticStagingConfig } from "./staging-preflight.mjs";

const REPO = "nafisajuliansahsaputra/smart-cafe-system";
function pr(number, head, base, headRepo = REPO) {
  return { number, draft: true,
    head: { ref: head, sha: ("f"+String(number)).padEnd(40,"0"),
      repo: { full_name: headRepo } },
    base: { ref: base, repo: { full_name: REPO } } };
}
const single = () => [
  pr(3,"feat/release","feat/mid"),
  pr(1,"feat/root","main"),
  pr(2,"feat/mid","feat/root"),
];

test("linear stack checks root-to-tip refs", () => {
  const result = inspectReleaseChain(single(),"feat/release",REPO);
  assert.deepEqual(result.chain.map(x=>x.number),[1,2,3]);
  assert.equal(result.root,1);
  assert.equal(result.tip,3);
  assert.equal(result.otherOpenPrCount,0);
});
test("non-stack PRs counted but not mixed into release ancestry", () => {
  const result=inspectReleaseChain([...single(),pr(8,"feat/other","main")],
    "feat/release",REPO);
  assert.equal(result.otherOpenPrCount,1);
  assert.deepEqual(result.excludedRefs,["feat/other"]);
});
test("missing parent/current PR fails closed", () => {
  assert.throws(()=>inspectReleaseChain(single().slice(0,2),
    "feat/release",REPO),/Missing/);
  assert.throws(()=>inspectReleaseChain([], "feat/release",REPO),/No open/);
  assert.throws(()=>inspectReleaseChain(single(),"not-a-pr",REPO),/Missing/);
});
test("cycles and self-based PRs fail closed", () => {
  assert.throws(()=>inspectReleaseChain([
    pr(2,"feat/a","feat/b"),pr(3,"feat/b","feat/a")],
    "feat/a",REPO),/Cycle/);
  assert.throws(()=>inspectReleaseChain([pr(2,"feat/a","feat/a")],
    "feat/a",REPO),/Self-based/);
});
test("forks cannot enter same-repository chain", () => {
  assert.throws(()=>inspectReleaseChain([
    pr(1,"feat/root","main","unrelated/fork")],
    "feat/root",REPO),/Missing/);
});
test("rejects malformed and duplicate refs", () => {
  assert.throws(()=>inspectReleaseChain([
    pr(1,"x","main"),pr(2,"x","main")],"x",REPO),/Duplicate/);
  assert.throws(()=>inspectReleaseChain([
    {number:1,head:{ref:"x"}}],"x",REPO),/Incomplete/);
});

function secureConfig() {
  return { services: {
    db: { environment: {
      POSTGRES_DB:"smart_cafe_staging_ci",POSTGRES_USER:"scs_ci",
      POSTGRES_PASSWORD:"synthetic-staging-test-password-very-long",
    },ports:[{host_ip:"127.0.0.1",published:"5432",target:5432}] },
    api: {read_only:true,cap_drop:["ALL"],pids_limit:512,
      security_opt:["no-new-privileges:true"],
      ports:[{host_ip:"127.0.0.1",published:"8080",target:8080}],
      environment:{
        DB_URL:"jdbc:postgresql://db:5432/smart_cafe_staging_ci",
        DB_USERNAME:"scs_ci",
        DB_PASSWORD:"synthetic-staging-test-password-very-long",
        SCS_PAYMENT_ENVIRONMENT:"sandbox",
        SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED:"true",
        API_DOCS_ENABLED:"false",
        SCS_JOIN_THROTTLE_SECRET:"synthetic-staging-test-hmac-secret-do-not-deploy",
        SPRING_PROFILES_ACTIVE:"",
        SCS_BOOTSTRAP_USERNAME:"",
        SCS_BOOTSTRAP_PASSWORD:"",
      } },
    web: {read_only:true,cap_drop:["ALL"],pids_limit:512,
      security_opt:["no-new-privileges:true"],
      ports:[{host_ip:"127.0.0.1",published:"3000",target:3000}],
      environment:{
        API_INTERNAL_URL:"http://api:8080",NODE_ENV:"production",
        SCS_PAYMENT_ENVIRONMENT:"sandbox",
        SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED:"true",
      } },
  } };
}
test("strictly isolated synthetic staging rehearsal passes",() => {
  assert.deepEqual(inspectSyntheticStagingConfig(secureConfig()),[]);
});
test("public database or web binds fail closed",() => {
  const x=secureConfig();
  x.services.db.ports[0].host_ip="0.0.0.0";
  x.services.web.ports[0].host_ip="";
  assert.equal(inspectSyntheticStagingConfig(x).length,2);
});
test("production mode, public docs and bootstrap are rejected",()=>{
  const x=secureConfig();
  x.services.api.environment.SCS_PAYMENT_ENVIRONMENT="production";
  x.services.api.environment.SPRING_PROFILES_ACTIVE="dev-bootstrap";
  x.services.api.environment.API_DOCS_ENABLED="true";
  x.services.web.environment.SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED="false";
  assert.ok(inspectSyntheticStagingConfig(x).length>=4);
});
test("unsafe DB destination, credentials and runtime mode are rejected",()=>{
  const x=secureConfig();
  x.services.db.environment.POSTGRES_DB="production";
  x.services.api.environment.DB_URL="jdbc:postgresql://prod:5432/live";
  x.services.api.environment.DB_PASSWORD="password";
  x.services.api.read_only=false;
  x.services.web.security_opt=[];
  x.services.web.network_mode="host";
  assert.ok(inspectSyntheticStagingConfig(x).length>=5);
});
test("missing database service is not silently allowed",()=>{
  const x=secureConfig();
  delete x.services.db;
  assert.ok(inspectSyntheticStagingConfig(x).includes("Missing service: db"));
});
