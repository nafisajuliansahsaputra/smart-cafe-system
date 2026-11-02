import test from "node:test";
import assert from "node:assert/strict";
import { createSbomEvidence, sha256, validateCycloneDx } from "./verify-image-sbom.mjs";

const sourceSha = "a".repeat(40);
const imageId = "sha256:" + "b".repeat(64);
const bom = {
  bomFormat: "CycloneDX",
  specVersion: "1.6",
  version: 1,
  components: [
    { type: "library", name: "a-dependency", version: "1.2.3" },
    { type: "operating-system", name: "sample-linux" },
  ],
};
function params() {
  return {
    component: "api", sourceSha, imageId,
    imageReference: "scs-ci-api:" + sourceSha,
    dockerfilePath: "backend/Dockerfile",
    dockerfileBytes: Buffer.from("FROM non-existent-test-base\n"),
    bomBytes: Buffer.from(JSON.stringify(bom)),
  };
}
test("valid CycloneDX and exact local build evidence", () => {
  const input = params();
  const actual = createSbomEvidence(input);
  assert.equal(actual.sourceCommit, sourceSha);
  assert.equal(actual.localImageId, imageId);
  assert.equal(actual.componentCount, 2);
  assert.equal(actual.sbomSha256, sha256(input.bomBytes));
  assert.equal(actual.dockerfileSha256, sha256(input.dockerfileBytes));
  assert.equal(actual.evidenceType, "UNSIGNED_LOCAL_CI_RUNTIME_SBOM");
  assert.ok(!JSON.stringify(actual).includes("attestation\":\"signed"));
});
test("web has a separate trusted Dockerfile and tag", () => {
  const input = params();
  input.component = "web";
  input.imageReference = "scs-ci-web:" + sourceSha;
  input.dockerfilePath = "apps/web/Dockerfile";
  assert.equal(createSbomEvidence(input).component, "web");
});
test("missing or false package inventory is rejected", () => {
  for (const invalid of [
    {}, null, [], { ...bom, components: [] },
    { ...bom, bomFormat: "SPDX" },
    { ...bom, specVersion: "not-a-version" },
    { ...bom, version: 0 },
    { ...bom, components: [{ type: "library", name: "" }] },
    { ...bom, components: [{ name: "partial" }] },
  ]) {
    assert.throws(() => validateCycloneDx(invalid));
  }
  const input = params();
  input.bomBytes = Buffer.from("{truncated");
  assert.throws(() => createSbomEvidence(input), /SBOM validation failed/);
});
test("wrong source SHA, tag, component or image ID fails closed", () => {
  for (const key of ["sourceSha", "imageReference", "imageId", "component"]) {
    const input = params();
    input[key] = "untrusted";
    assert.throws(() => createSbomEvidence(input));
  }
  const input = params();
  input.sourceSha = "f".repeat(40);
  assert.throws(() => createSbomEvidence(input), /tag differs/);
});
test("Dockerfile cannot be substituted across components", () => {
  const input = params();
  input.dockerfilePath = "apps/web/Dockerfile";
  assert.throws(() => createSbomEvidence(input));
  input.dockerfilePath = "../backend/Dockerfile";
  assert.throws(() => createSbomEvidence(input));
  input.dockerfilePath = "backend/Dockerfile";
  input.dockerfileBytes = Buffer.alloc(0);
  assert.throws(() => createSbomEvidence(input));
});
test("oversized, empty and malformed SBOM fail", () => {
  const input = params();
  input.bomBytes = Buffer.alloc(0);
  assert.throws(() => createSbomEvidence(input));
  input.bomBytes = Buffer.alloc(50 * 1024 * 1024 + 1);
  assert.throws(() => createSbomEvidence(input));
});
