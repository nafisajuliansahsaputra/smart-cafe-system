import test from "node:test";
import assert from "node:assert/strict";
import { inspectIsolatedRehearsalConfig } from "./isolated-staging-preflight.mjs";

function staging() {
  const secret="synthetic-ci-only-secret-unique-please-never-deploy";
  return {
    name:"scs-staging-rehearsal",
    services:{
      db:{
        restart:"no",networks:{data:null},
        environment:{POSTGRES_DB:"smart_cafe_staging_rehearsal",
          POSTGRES_USER:"scs_ci",POSTGRES_PASSWORD:secret},
        volumes:[{type:"volume",source:"scs-staging-rehearsal_staging_postgres_data",
          target:"/var/lib/postgresql/data"}],
      },
      api:{
        restart:"no",read_only:true,cap_drop:["ALL"],
        security_opt:["no-new-privileges:true"],pids_limit:512,
        networks:{data:null,frontend:null},
        ports:[{host_ip:"127.0.0.1",published:"18080",target:8080}],
        environment:{
          DB_URL:"jdbc:postgresql://db:5432/smart_cafe_staging_rehearsal",
          DB_USERNAME:"scs_ci",DB_PASSWORD:secret,
          API_DOCS_ENABLED:"false",SCS_JOIN_THROTTLE_SECRET:secret,
          SCS_PAYMENT_ENVIRONMENT:"sandbox",
          SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED:"true",
          SCS_SANDBOX_WEBHOOK_SECRET:"",
          SPRING_PROFILES_ACTIVE:"",
          SCS_BOOTSTRAP_USERNAME:"",SCS_BOOTSTRAP_PASSWORD:"",
        },
      },
      web:{
        restart:"no",read_only:true,cap_drop:["ALL"],
        security_opt:["no-new-privileges:true"],pids_limit:512,
        networks:{frontend:null},
        ports:[{host_ip:"127.0.0.1",published:"13080",target:3000}],
        environment:{API_INTERNAL_URL:"http://api:8080",NODE_ENV:"production",
          SCS_PAYMENT_ENVIRONMENT:"sandbox",
          SCS_PAYMENT_SANDBOX_CHECKOUT_ENABLED:"true"},
      },
    },
    networks:{data:{internal:true},frontend:{}},
    volumes:{staging_postgres_data:{name:"scs-staging-rehearsal_staging_postgres_data"}},
  };
}
test("isolated local staging topology passes",()=>{
  assert.deepEqual(inspectIsolatedRehearsalConfig(staging()),[]);
});
test("database published even to localhost is rejected",()=>{
  const x=staging();
  x.services.db.ports=[{host_ip:"127.0.0.1",published:"15432",target:5432}];
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/no published ports/);
});
test("external ingress is rejected",()=>{
  const x=staging();x.services.api.ports[0].host_ip="0.0.0.0";
  assert.ok(inspectIsolatedRehearsalConfig(x).length>0);
});
test("sandbox webhook credential must remain disabled",()=>{
  const x=staging();x.services.api.environment.SCS_SANDBOX_WEBHOOK_SECRET="not-safe";
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/callback/);
});
test("data network must be internal, not shared or external",()=>{
  const x=staging();x.networks.data.internal=false;
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/Networks/);
});
test("web must never join DB network",()=>{
  const x=staging();x.services.web.networks.data=null;
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/segregation/);
});
test("host bind and externally managed volume are rejected",()=>{
  const x=staging();
  x.services.db.volumes=[{type:"bind",source:"/var/lib/real-db",target:"/data"}];
  x.volumes.staging_postgres_data.external=true;
  assert.ok(inspectIsolatedRehearsalConfig(x).length>=2);
});
test("bootstrap staff, public docs or production money mode rejected",()=>{
  const x=staging();
  x.services.api.environment.SPRING_PROFILES_ACTIVE="dev-bootstrap";
  x.services.api.environment.API_DOCS_ENABLED="true";
  x.services.web.environment.SCS_PAYMENT_ENVIRONMENT="production";
  assert.ok(inspectIsolatedRehearsalConfig(x).length>=3);
});
test("no unrelated service or automatic restart allowed",()=>{
  const x=staging();x.services.api.restart="always";
  x.services.worker={ports:[]};
  assert.ok(inspectIsolatedRehearsalConfig(x).length>=2);
});
test("rehearsal must use reserved project/ports",()=>{
  const x=staging();
  x.name="smart-cafe-system";
  x.services.web.ports[0].published="3000";
  assert.ok(inspectIsolatedRehearsalConfig(x).length>=2);
});
