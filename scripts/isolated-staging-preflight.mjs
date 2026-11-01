#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { inspectSyntheticStagingConfig } from "./staging-preflight.mjs";
const keys=x=>x&&typeof x==="object"?Object.keys(x):[];
const port=(svc,target,published)=>
  Array.isArray(svc.ports)&&svc.ports.length===1&&
  svc.ports[0]?.host_ip==="127.0.0.1"&&
  Number(svc.ports[0]?.target)===target&&
  String(svc.ports[0]?.published)===published;

/** Stronger constraints specific to running the CI-only staging stack. */
export function inspectIsolatedRehearsalConfig(config) {
  const issues=inspectSyntheticStagingConfig(config);
  if(issues.length)return issues;
  const reject=s=>issues.push(s);
  const {db,api,web}=config.services;
  if(config.name!=="scs-staging-rehearsal")reject("Unexpected Compose project name");
  if(keys(config.services).sort().join(",")!=="api,db,web")reject("Unexpected services");
  if((db.ports??[]).length)reject("Database must have no published ports");
  if(!port(api,8080,"18080"))reject("API must bind only 127.0.0.1:18080");
  if(!port(web,3000,"13080"))reject("Web must bind only 127.0.0.1:13080");
  for(const [name,svc] of Object.entries(config.services)) {
    if(svc.restart!=="no")reject(name+": restart must be disabled");
    if((svc.volumes??[]).some(v=>v.type!=="volume"||v.bind||
      String(v.source??"").startsWith("/"))) {
      reject(name+": host bind mounts forbidden");
    }
    if(svc.devices||svc.extra_hosts||svc.secrets)reject(name+": host capability forbidden");
  }
  if(api.environment.SCS_SANDBOX_WEBHOOK_SECRET!=="") {
    reject("Sandbox callback must remain disabled");
  }
  const nets=config.networks??{};
  if(keys(nets).sort().join(",")!=="data,frontend"||
    nets.data?.internal!==true||nets.data?.external===true||
    !nets.frontend||nets.frontend.external===true) {
    reject("Networks must be managed data (internal) and frontend only");
  }
  if(keys(db.networks).join(",")!=="data"||
    keys(web.networks).join(",")!=="frontend"||
    keys(api.networks).sort().join(",")!=="data,frontend") {
    reject("DB, API and web must have strict private network segregation");
  }
  const vols=config.volumes??{};
  if(keys(vols).length!==1||!vols.staging_postgres_data||
    vols.staging_postgres_data.external===true) {
    reject("Single non-external staging DB volume required");
  }
  return issues;
}
async function main(){
  if(process.argv.length!==3)throw Error("Usage: node scripts/isolated-staging-preflight.mjs <resolved-compose.json>");
  const config=JSON.parse(await readFile(process.argv[2],"utf8"));
  const issues=inspectIsolatedRehearsalConfig(config);
  if(issues.length)throw Error(issues.join("; "));
  process.stdout.write("STAGING RUNTIME PREFLIGHT PASSED: private DB network, no DB ingress, sandbox checkout and local-only web/API.\n");
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  main().catch(e=>{process.stderr.write("STAGING RUNTIME PREFLIGHT FAILED: "+e.message+"\n");process.exitCode=1;});
}
