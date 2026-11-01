import test from "node:test";
import assert from "node:assert/strict";
import { inspectStagingRuntime } from "./isolated-staging-runtime-inspect.mjs";

const project = "scs-staging-rehearsal";
const data = project + "_data";
const frontend = project + "_frontend";
function fixture() {
  const container = (service, networkNames) => ({
    Id: "synthetic-id-" + service,
    State: { Running: true },
    Config: {
      Labels: {
        "com.docker.compose.project": project,
        "com.docker.compose.service": service
      },
      User: service === "web" ? "node" : service === "api" ? "cafe" : "postgres"
    },
    HostConfig: {
      Privileged: false,
      CapAdd: null,
      Devices: [],
      Binds: null,
      VolumesFrom: null,
      PidMode: "",
      IpcMode: "",
      UsernsMode: "",
      NetworkMode: networkNames[0],
      ReadonlyRootfs: service !== "db",
      PidsLimit: service === "db" ? 0 : 512,
      CapDrop: service === "db" ? null : ["ALL"],
      SecurityOpt: service === "db" ? null : ["no-new-privileges:true"],
      PortBindings: service === "db" ? {} : {
        [service === "api" ? "8080/tcp" : "3000/tcp"]: [{
          HostIp: "127.0.0.1",
          HostPort: service === "api" ? "18080" : "13080"
        }]
      },
      Tmpfs: service === "db" ? null : {
        "/tmp": "rw,nosuid,nodev,size=67108864",
        ...(service === "web"
          ? { "/app/.next/cache": "rw,nosuid,nodev,size=134217728" } : {})
      }
    },
    Mounts: service === "db" ? [{
      Type: "volume", Name: project + "_staging_postgres_data",
      Destination: "/var/lib/postgresql/data", RW: true
    }] : [],
    NetworkSettings: {
      Networks: Object.fromEntries(networkNames.map(n => [n, {}]))
    }
  });
  const containers = [
    container("db", [data]),
    container("api", [data, frontend]),
    container("web", [frontend])
  ];
  const networks = [
    { Name: data, Internal: true,
      Labels: { "com.docker.compose.project": project },
      Containers: Object.fromEntries(containers.slice(0,2).map(c => [c.Id, {}])) },
    { Name: frontend, Internal: false,
      Labels: { "com.docker.compose.project": project },
      Containers: Object.fromEntries(containers.slice(1).map(c => [c.Id, {}])) }
  ];
  return { containers, networks };
}
function errs(f) { return inspectStagingRuntime(f.containers, f.networks).join(";"); }

test("clean isolated Docker runtime is accepted", () => {
  const f = fixture();
  assert.deepEqual(inspectStagingRuntime(f.containers, f.networks), []);
});
test("published PostgreSQL port is denied", () => {
  const f = fixture();
  f.containers[0].HostConfig.PortBindings = { "5432/tcp": [{HostIp:"127.0.0.1", HostPort:"15432"}] };
  assert.match(errs(f), /published host port/);
});
test("web port bound publicly is denied", () => {
  const f = fixture(); f.containers[2].HostConfig.PortBindings["3000/tcp"][0].HostIp="0.0.0.0";
  assert.match(errs(f), /restricted to loopback/);
});
test("privileged API and added Linux capability are denied", () => {
  const f = fixture();
  f.containers[1].HostConfig.Privileged = true;
  f.containers[1].HostConfig.CapAdd = ["SYS_ADMIN"];
  assert.match(errs(f), /forbidden host privilege/);
});
test("unexpected container network membership is denied", () => {
  const f = fixture(); f.containers[2].NetworkSettings.Networks[data] = {};
  assert.match(errs(f), /unexpected runtime network attachment/);
});
test("foreign network sharing is denied even if containers look correct", () => {
  const f = fixture();
  f.networks[0].Containers["unrelated-service"] = {};
  assert.match(errs(f), /unexpected connected containers/);
});
test("database network must be internal and from correct project", () => {
  const f = fixture(); f.networks[0].Internal=false;
  f.networks[0].Labels["com.docker.compose.project"]="production";
  assert.match(errs(f), /foreign or unsafe Docker network/);
});
test("shared or read-only database mount is denied", () => {
  const f = fixture();
  f.containers[0].Mounts[0].Name="production_postgres_data";
  f.containers[0].Mounts[0].RW=false;
  assert.match(errs(f), /dedicated writable PostgreSQL volume/);
});
test("app mounted volume and root user are denied", () => {
  const f = fixture();
  f.containers[1].Mounts=[{Type:"bind",Destination:"/app"}];
  f.containers[1].Config.User="root";
  assert.match(errs(f), /unexpected host or volume mount/);
  assert.match(errs(f), /unprivileged container user/);
});
test("read-only, seccomp and tmpfs protections cannot disappear", () => {
  const f = fixture();
  f.containers[2].HostConfig.ReadonlyRootfs=false;
  f.containers[2].HostConfig.SecurityOpt=["no-new-privileges:true","seccomp:unconfined"];
  delete f.containers[2].HostConfig.Tmpfs["/app/.next/cache"];
  assert.match(errs(f), /runtime privilege hardening/);
  assert.match(errs(f), /missing bounded temporary filesystem/);
});
test("unexpected service or stopped container is denied", () => {
  const f = fixture();
  f.containers[0].State.Running=false;
  f.containers[2].Config.Labels["com.docker.compose.service"]="worker";
  assert.match(errs(f), /not running/);
  assert.match(errs(f), /Exactly three/);
});
