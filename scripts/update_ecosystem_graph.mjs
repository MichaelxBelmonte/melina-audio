#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const fileFlag = process.argv.indexOf("--file");
const dataPath = fileFlag >= 0 && process.argv[fileFlag + 1]
  ? resolve(process.cwd(), process.argv[fileFlag + 1])
  : resolve(root, "docs/data/ecosystem.json");
const checkOnly = process.argv.includes("--check");
const githubToken = process.env.GITHUB_TOKEN || "";

const raw = await readFile(dataPath, "utf8");
const atlas = JSON.parse(raw);
const nodes = Array.isArray(atlas.nodes) ? atlas.nodes : [];
const githubRepos = [...new Set(nodes.map((node) => node.githubRepo).filter(Boolean))];
const hfModels = [...new Set(nodes.map((node) => node.hfModel).filter(Boolean))];

async function fetchJson(url, headers = {}) {
  const response = await fetch(url, {
    headers: {
      "user-agent": "melina-ecosystem-atlas",
      accept: "application/json",
      ...headers
    }
  });
  if (!response.ok) {
    const rateLimit = response.headers.get("x-ratelimit-remaining");
    throw new Error(`${response.status} ${response.statusText}${rateLimit === "0" ? " (rate limit exhausted)" : ""}`);
  }
  return response.json();
}

async function concurrentMap(items, concurrency, task) {
  const results = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while (nextIndex < items.length) {
      const index = nextIndex;
      nextIndex += 1;
      try {
        results[index] = { status: "fulfilled", value: await task(items[index]) };
      } catch (error) {
        results[index] = { status: "rejected", reason: error };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker));
  return results;
}

const githubHeaders = githubToken ? { authorization: `Bearer ${githubToken}` } : {};
const githubResults = await concurrentMap(githubRepos, 4, async (repo) => {
  const payload = await fetchJson(`https://api.github.com/repos/${repo}`, githubHeaders);
  return {
    repo,
    stars: payload.stargazers_count,
    forks: payload.forks_count,
    openIssues: payload.open_issues_count,
    pushedAt: payload.pushed_at,
    archived: payload.archived,
    defaultBranch: payload.default_branch,
    url: payload.html_url
  };
});

const hfResults = await concurrentMap(hfModels, 4, async (model) => {
  const payload = await fetchJson(`https://huggingface.co/api/models/${model}`);
  return {
    model,
    downloads: payload.downloads,
    likes: payload.likes,
    lastModified: payload.lastModified,
    pipelineTag: payload.pipeline_tag,
    url: `https://huggingface.co/${model}`
  };
});

const githubMetrics = new Map();
const hfMetrics = new Map();
const failures = [];

githubResults.forEach((result, index) => {
  if (result.status === "fulfilled") githubMetrics.set(result.value.repo, result.value);
  else failures.push(`GitHub ${githubRepos[index]}: ${result.reason.message}`);
});

hfResults.forEach((result, index) => {
  if (result.status === "fulfilled") hfMetrics.set(result.value.model, result.value);
  else failures.push(`Hugging Face ${hfModels[index]}: ${result.reason.message}`);
});

nodes.forEach((node) => {
  node.metrics ||= {};
  const github = githubMetrics.get(node.githubRepo);
  if (github) {
    Object.assign(node.metrics, {
      stars: github.stars,
      forks: github.forks,
      openIssues: github.openIssues,
      repositoryUpdatedAt: github.pushedAt
    });
    node.repositoryArchived = github.archived;
  }
  const huggingFace = hfMetrics.get(node.hfModel);
  if (huggingFace) {
    Object.assign(node.metrics, {
      downloads: huggingFace.downloads,
      hfLikes: huggingFace.likes,
      modelUpdatedAt: huggingFace.lastModified
    });
  }
});

const timestamp = new Date().toISOString();
atlas.meta ||= {};
if (githubMetrics.size || hfMetrics.size) atlas.meta.updatedAt = timestamp;
atlas.meta.liveMetrics = {
  updatedAt: timestamp,
  github: { requested: githubRepos.length, updated: githubMetrics.size },
  huggingFace: { requested: hfModels.length, updated: hfMetrics.size },
  failures
};

if (!checkOnly) await writeFile(dataPath, `${JSON.stringify(atlas, null, 2)}\n`, "utf8");

console.log(`Atlas metrics: ${githubMetrics.size}/${githubRepos.length} GitHub repositories, ${hfMetrics.size}/${hfModels.length} Hugging Face models.`);
failures.forEach((failure) => console.warn(`warning: ${failure}`));
if (checkOnly) console.log("Check only: no file written.");
