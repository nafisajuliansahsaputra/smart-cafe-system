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
    networks:{
      data:{internal:true,name:"scs-staging-rehearsal_data"},
      frontend:{name:"scs-staging-rehearsal_frontend"},
    },
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

test("privileged capability cannot be re-added after cap_drop ALL",()=>{
  const x=staging();x.services.api.cap_add=["SYS_ADMIN"];
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/cap_add/);
});
test("additional unconfined seccomp security option is rejected",()=>{
  const x=staging();x.services.web.security_opt=[
    "no-new-privileges:true","seccomp:unconfined"
  ];
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/no-new-privileges/);
});
test("host user namespace and volume inheritance are rejected",()=>{
  const x=staging();x.services.api.userns_mode="host";
  x.services.web.volumes_from=["db"];
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/userns_mode/);
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/volumes_from/);
});
test("application containers may not mount reusable volumes",()=>{
  const x=staging();x.services.web.volumes=[
    {type:"volume",source:"staging_other",target:"/app/secrets"}
  ];
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/must not mount volumes/);
});
test("database may only mount dedicated writable database volume",()=>{
  const x=staging();
  x.services.db.volumes[0].source="some_other_project_data";
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/dedicated writable/);
  x.services.db.volumes[0].source="scs-staging-rehearsal_staging_postgres_data";
  x.services.db.volumes[0].target="/host-data";
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/dedicated writable/);
});
test("project network and database volume names cannot be shared",()=>{
  const x=staging();x.networks.frontend.name="production_frontend";
  x.volumes.staging_postgres_data.name="production_postgres_data";
  const errors=inspectIsolatedRehearsalConfig(x).join(";");
  assert.match(errors,/project-isolated/);
  assert.match(errors,/project-specific/);
});
test("extra host DNS and device permissions are forbidden",()=>{
  const x=staging();x.services.api.extra_hosts=["merchant-db:10.0.0.2"];
  x.services.db.devices=["/dev/sda:/dev/sda"];
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/extra_hosts/);
  assert.match(inspectIsolatedRehearsalConfig(x).join(";"),/devices/);
});
