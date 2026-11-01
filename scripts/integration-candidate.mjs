#!/usr/bin/env node
import { pathToFileURL } from "node:url";
import { fetchOpenPulls, inspectReleaseChain, verifyGitAncestry } from "./release-chain.mjs";

const SHA=/^[0-9a-f]{40}$/;

/**
 * A release candidate MUST be an unchanged copy of exactly one stacked
 * PR tip. A direct-to-main PR is not proof the full dependency stack was read.
 */
export function inspectIntegrationCandidate(pulls,candidateBranch,repository) {
  if (typeof candidateBranch!=="string" ||
      !/^integration\/[A-Za-z0-9._/-]+$/.test(candidateBranch)) {
    throw new Error("Integration candidate must use integration/ branch prefix");
  }
  if (!Array.isArray(pulls) || pulls.length===0) {
    throw new Error("Open pull request metadata is required");
  }
  const relevant=pulls.filter(p=>p?.head?.repo?.full_name===repository &&
    p?.base?.repo?.full_name===repository);
  const matches=relevant.filter(p=>p.head.ref===candidateBranch);
  if (matches.length!==1) {
    throw new Error("Expected exactly one same-repo candidate PR");
  }
  const candidate=matches[0];
  if (candidate.base.ref!=="main" || candidate.draft!==true) {
    throw new Error("Candidate must be a draft PR directly into main");
  }
  if (!SHA.test(candidate.head.sha)) {
    throw new Error("Candidate commit SHA is malformed");
  }
  const sources=relevant.filter(p=>
    p.number!==candidate.number && p.head.sha===candidate.head.sha &&
    p.base.ref!=="main" && p.draft===true &&
    !p.head.ref.startsWith("integration/"));
  if (sources.length!==1) {
    throw new Error("Candidate must exactly match one stacked draft PR head SHA");
  }
  const source=sources[0];
  const lineage=inspectReleaseChain(pulls,source.head.ref,repository);
  if (lineage.chain.length<2 || lineage.chain.at(-1).headSha!==candidate.head.sha) {
    throw new Error("Candidate source must be an intact multi-PR lineage");
  }
  if (lineage.chain.some(p=>p.draft!==true)) {
    throw new Error("A candidate cannot bypass a non-draft parent PR");
  }
  return {
    candidateNumber:candidate.number,
    candidateBranch:candidate.head.ref,
    candidateSha:candidate.head.sha,
    sourceNumber:source.number,
    sourceBranch:source.head.ref,
    chain:lineage.chain,
  };
}

async function github(path,token) {
  const res=await fetch("https://api.github.com"+path,{
    headers:{
      Accept:"application/vnd.github+json",
      Authorization:"Bearer "+token,
      "X-GitHub-Api-Version":"2022-11-28",
      "User-Agent":"scs-integration-candidate-readonly",
    },
    signal:AbortSignal.timeout(15000),
  });
  if (!res.ok) throw new Error("GitHub verification returned HTTP "+res.status);
  return res.json();
}

async function run() {
  const repo=process.env.GITHUB_REPOSITORY;
  const token=process.env.GITHUB_TOKEN;
  const candidateBranch=process.env.SCS_INTEGRATION_CANDIDATE_BRANCH;
  if (!repo || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo) ||
      !token || !candidateBranch) {
    throw new Error("GitHub repository/token and candidate branch are required");
  }
  const pulls=await fetchOpenPulls(repo,token);
  const result=inspectIntegrationCandidate(pulls,candidateBranch,repo);

  // Verify main is a commit ancestor, not merely the textual PR base.
  const main=await github("/repos/"+repo+"/branches/main",token);
  const mainSha=main?.commit?.sha;
  if (!SHA.test(mainSha??"")) throw new Error("Unable to resolve main SHA");
  const compare=await github("/repos/"+repo+"/compare/"+
    mainSha+"..."+result.candidateSha,token);
  if (compare.status!=="ahead" || compare.behind_by!==0 ||
      !Number.isInteger(compare.ahead_by) || compare.ahead_by<=0) {
    throw new Error("Candidate is not strictly ahead of current main");
  }
  if (process.argv.includes("--verify-ancestry")) {
    await verifyGitAncestry(repo,token,result.chain);
  }
  process.stdout.write(
    "INTEGRATION PROVENANCE PASSED: candidate #"+result.candidateNumber+
    " is draft direct-to-main and exactly matches source #"+
    result.sourceNumber+" ("+result.candidateSha.slice(0,12)+").\n"+
    "Complete stacked lineage contains "+result.chain.length+
    " draft PRs from main. Main ancestry verified, ahead "+
    compare.ahead_by+", behind "+compare.behind_by+".\n"+
    (process.argv.includes("--verify-ancestry")?
      "Every parent commit ancestry verified.\n":
      "Detailed parent commit ancestry available via --verify-ancestry.\n")+
    "NO MERGE, NO DEPLOYMENT, NO FINANCIAL ACTION PERFORMED.\n");
}

if (process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  run().catch(error=>{
    process.stderr.write("INTEGRATION PROVENANCE FAILED: "+error.message+"\n");
    process.exitCode=1;
  });
}
