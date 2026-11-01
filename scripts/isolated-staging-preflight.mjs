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
  // Reject Linux capabilities, host namespaces and alternate host resource
  // attachments that would bypass the disposable container policy.
  const forbiddenRuntimeFields=[
    "cap_add","userns_mode","cgroup","cgroupns_mode","uts","pid","ipc",
    "network_mode","devices","device_cgroup_rules","volumes_from",
    "extra_hosts","links","external_links","dns","dns_search","dns_opt",
    "sysctls","secrets"
  ];
  for(const [name,svc] of Object.entries(config.services)) {
    for(const field of forbiddenRuntimeFields) {
      if(Object.hasOwn(svc,field))reject(name+": forbidden runtime option "+field);
    }
    if(name!=="db" &&
      (!Array.isArray(svc.security_opt)||
       svc.security_opt.length!==1||
       svc.security_opt[0]!=="no-new-privileges:true")) {
      reject(name+": exactly no-new-privileges:true is required");
    }
    if(name==="db" && Object.hasOwn(svc,"security_opt")) {
      reject("db: unexpected security_opt");
    }
    if(svc.restart!=="no")reject(name+": restart must be disabled");
    if((svc.volumes??[]).some(v=>v.type!=="volume"||v.bind||
      String(v.source??"").startsWith("/"))) {
      reject(name+": host bind mounts forbidden");
    }
    if(name!=="db" && (svc.volumes??[]).length) {
      reject(name+": application containers must not mount volumes");
    }
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
  if(nets.data?.name!=="scs-staging-rehearsal_data"||
    nets.frontend?.name!=="scs-staging-rehearsal_frontend") {
    reject("Staging network names must remain project-isolated");
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
  const expectedVolume="scs-staging-rehearsal_staging_postgres_data";
  if(vols.staging_postgres_data?.name!==expectedVolume||
    vols.staging_postgres_data?.driver_opts) {
    reject("Database volume must have an isolated project-specific name");
  }
  const dbMounts=db.volumes??[];
  if(!Array.isArray(dbMounts)||dbMounts.length!==1||
    dbMounts[0]?.type!=="volume"||
    dbMounts[0]?.source!==expectedVolume||
    dbMounts[0]?.target!=="/var/lib/postgresql/data"||
    dbMounts[0]?.read_only===true) {
    reject("Database must mount only its dedicated writable staging volume");
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
