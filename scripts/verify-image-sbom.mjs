#!/usr/bin/env node
/**
 * Validate a CycloneDX SBOM from an already built local runtime image and
 * attach unsigned, independently checkable build facts to the CI artifact.
 *
 * Never claim signature, registry digest, SLSA provenance, or reproducibility:
 * Docker's local image ID is not an immutable registry manifest digest.
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const contexts = Object.freeze({
  api: "backend/Dockerfile",
  web: "apps/web/Dockerfile",
});

const hex40 = /^[0-9a-f]{40}$/;
const imageIdPattern = /^sha256:[0-9a-f]{64}$/;

export function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

export function validateCycloneDx(bom) {
  if (bom === null || typeof bom !== "object" || Array.isArray(bom) ||
      bom.bomFormat !== "CycloneDX" ||
      typeof bom.specVersion !== "string" ||
      !/^1\.[0-9]+$/.test(bom.specVersion) ||
      !Number.isSafeInteger(bom.version) || bom.version < 1 ||
      !Array.isArray(bom.components) || bom.components.length === 0) {
    throw new Error("CycloneDX must have valid format, version and nonempty component inventory");
  }
  for (const component of bom.components) {
    if (!component || typeof component !== "object" ||
        typeof component.name !== "string" ||
        component.name.trim() === "" ||
        typeof component.type !== "string" ||
        component.type.trim() === "") {
      throw new Error("CycloneDX contains an incomplete package component");
    }
  }
  return { componentCount: bom.components.length, specVersion: bom.specVersion };
}

export function createSbomEvidence({
  component, sourceSha, imageReference, imageId, bomBytes,
  dockerfileBytes, dockerfilePath,
}) {
  if (!Object.hasOwn(contexts, component)) {
    throw new Error("Unsupported release image component");
  }
  if (!hex40.test(sourceSha ?? "")) {
    throw new Error("Invalid exact source commit SHA");
  }
  if (imageReference !== "scs-ci-" + component + ":" + sourceSha) {
    throw new Error("Local runtime image tag differs from exact source commit");
  }
  if (!imageIdPattern.test(imageId ?? "")) {
    throw new Error("Missing or invalid Docker local image ID");
  }
  if (dockerfilePath !== contexts[component] ||
      !Buffer.isBuffer(dockerfileBytes) || dockerfileBytes.length === 0) {
    throw new Error("Wrong or empty Dockerfile for this runtime image");
  }
  if (!Buffer.isBuffer(bomBytes) || bomBytes.length === 0 ||
      bomBytes.length > 50 * 1024 * 1024) {
    throw new Error("Empty or unreasonably large SBOM");
  }
  let inventory;
  try {
    inventory = validateCycloneDx(JSON.parse(bomBytes.toString("utf8")));
  } catch (error) {
    throw new Error("SBOM validation failed: " + error.message);
  }
  return {
    evidenceVersion: 1,
    evidenceType: "UNSIGNED_LOCAL_CI_RUNTIME_SBOM",
    component,
    sourceCommit: sourceSha,
    localImageReference: imageReference,
    localImageId: imageId,
    dockerfilePath,
    dockerfileSha256: sha256(dockerfileBytes),
    sbomPath: "runtime-image.cdx.json",
    sbomSha256: sha256(bomBytes),
    bomFormat: "CycloneDX",
    bomSpecVersion: inventory.specVersion,
    componentCount: inventory.componentCount,
    scanner: "Trivy",
    scannerVersion: "0.76.1",
    limitations: [
      "Unsigned CI evidence, not a SLSA or Sigstore attestation",
      "Local Docker image ID is not a registry digest",
      "No container image was published or deployed",
    ],
  };
}

function main() {
  const [sbomPath, dockerfilePath, outputPath] = process.argv.slice(2);
  if (!sbomPath || !dockerfilePath || !outputPath) {
    throw new Error("Usage: node scripts/verify-image-sbom.mjs <sbom> <Dockerfile> <output.json>");
  }
  const evidence = createSbomEvidence({
    component: process.env.SCS_IMAGE_COMPONENT,
    sourceSha: process.env.SCS_SOURCE_SHA,
    imageReference: process.env.SCS_IMAGE_REFERENCE,
    imageId: process.env.SCS_IMAGE_ID,
    bomBytes: readFileSync(sbomPath),
    dockerfileBytes: readFileSync(dockerfilePath),
    dockerfilePath,
  });
  writeFileSync(outputPath, JSON.stringify(evidence, null, 2) + "\n", { mode: 0o600 });
  console.log("PASS: validated " + evidence.component + " runtime SBOM (" +
    evidence.componentCount + " components) and unsigned local build fingerprints");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error("FAIL: runtime SBOM evidence could not be verified: " + error.message);
    process.exitCode = 1;
  }
}
