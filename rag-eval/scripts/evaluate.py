"""Run reproducible OpsAgent retrieval evaluation against the local gateway."""

import argparse
import datetime as dt
import json
import math
import os
import pathlib
import re
import statistics
import subprocess
import time
import urllib.parse
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[2]
CASES = ROOT / "rag-eval" / "cases" / "retrieval_cases.jsonl"
ANSWER_CASES = ROOT / "rag-eval" / "cases" / "answer_cases.jsonl"


def request_json(url, method="GET", body=None, token=None, timeout=120):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def percentile(values, quantile):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


def unique_documents(rank, candidates):
    result = []
    for chunk_id in rank:
        document = candidates.get(str(chunk_id), {}).get("documentName", "")
        if document and document not in result:
            result.append(document)
    return result


def retrieval_metrics(results, pipeline, k):
    recalls = []
    reciprocal_ranks = []
    ndcgs = []
    for item in results:
        gold = set(item["case"].get("goldDocuments", []))
        if not gold:
            continue
        ranked = item["documents"][pipeline][:k]
        hits = [1 if document in gold else 0 for document in ranked]
        recalls.append(len(set(ranked) & gold) / len(gold))
        first = next((index + 1 for index, hit in enumerate(hits) if hit), None)
        reciprocal_ranks.append(0.0 if first is None else 1.0 / first)
        dcg = sum(hit / math.log2(index + 2) for index, hit in enumerate(hits))
        ideal = sum(1.0 / math.log2(index + 2) for index in range(min(len(gold), k)))
        ndcgs.append(0.0 if ideal == 0 else dcg / ideal)
    return {
        f"recall@{k}": statistics.fmean(recalls) if recalls else 0.0,
        f"mrr@{k}": statistics.fmean(reciprocal_ranks) if reciprocal_ranks else 0.0,
        f"ndcg@{k}": statistics.fmean(ndcgs) if ndcgs else 0.0,
    }


def git_commit():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.SubprocessError):
        return "unavailable"


def markdown(report):
    lines = [
        "# OpsAgent RAG 离线评测报告",
        "",
        f"- 时间：{report['configuration']['timestamp']}",
        f"- Git commit：`{report['configuration']['gitCommit']}`",
        f"- 有答案问题：{report['caseCount']}，无答案问题：{report['noAnswerCount']}",
        "",
        "| Pipeline | Recall@5 | Recall@10 | Recall@20 | MRR@10 | nDCG@10 | P50(ms) | P95(ms) | P99(ms) |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for name in ("BM25", "VECTOR", "HYBRID_RRF", "HYBRID_RERANK"):
        item = report["pipelines"][name]
        lines.append(
            f"| {name} | {item['recall@5']:.4f} | {item['recall@10']:.4f} | "
            f"{item['recall@20']:.4f} | {item['mrr@10']:.4f} | {item['ndcg@10']:.4f} | "
            f"{item['p50Ms']:.1f} | {item['p95Ms']:.1f} | {item['p99Ms']:.1f} |"
        )
    if report.get("answerMetrics"):
        answer = report["answerMetrics"]
        lines.extend([
            "",
            "## 回答与引用指标",
            "",
            f"- Citation Document Hit Rate：{answer['citationDocumentHitRate']:.4f}",
            f"- Citation Validity Rate：{answer['citationValidityRate']:.4f}",
            f"- Deterministic Answer Pass Rate：{answer['deterministicAnswerPassRate']:.4f}",
        ])
    lines.extend([
        "",
        "> 结果由当前索引实时运行生成；不同模型、索引和数据状态下不可直接横向复用。",
        "",
    ])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--token", default=os.environ.get("OPSAGENT_TOKEN"),
                        help="Existing access token (or OPSAGENT_TOKEN environment variable); preferred")
    parser.add_argument("--password", default=os.environ.get("OPSAGENT_PASSWORD"))
    parser.add_argument("--captcha-id", help="ID returned by GET /api/auth/captcha")
    parser.add_argument("--captcha-code", help="Characters read from the same captcha image")
    parser.add_argument("--include-answers", action="store_true")
    args = parser.parse_args()
    token = args.token
    if not token:
        if not all((args.password, args.captcha_id, args.captcha_code)):
            parser.error("Use --token / OPSAGENT_TOKEN, or provide password, --captcha-id and --captcha-code.")
        login = request_json(
            f"{args.base_url}/api/auth/login",
            "POST",
            {"username": args.username, "password": args.password,
             "captchaId": args.captcha_id, "captchaCode": args.captcha_code},
        )
        if login.get("code") != 0 or not login.get("data", {}).get("accessToken"):
            parser.error(login.get("message") or "Login failed; obtain a new captcha before retrying.")
        token = login["data"]["accessToken"]
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line]
    results = []
    latencies = {name: [] for name in ("BM25", "VECTOR", "HYBRID_RRF", "HYBRID_RERANK")}
    for case in cases:
        started = time.perf_counter()
        query = urllib.parse.urlencode({"query": case["question"], "topK": 30})
        response = request_json(
            f"{args.base_url}/api/rag/debug/search?{query}", token=token)
        elapsed_ms = (time.perf_counter() - started) * 1000
        data = response["data"]
        candidates = {str(row["chunkId"]): row for row in data.get("candidates", [])}
        durations = data.get("durationMillis", {})
        ranks = {
            "BM25": data.get("bm25Rank", []),
            "VECTOR": data.get("vectorRank", []),
            "HYBRID_RRF": data.get("rrfRank", []),
            "HYBRID_RERANK": data.get("rerankRank", []),
        }
        latencies["BM25"].append(float(durations.get("bm25", elapsed_ms)))
        latencies["VECTOR"].append(float(durations.get("embedding", 0)) + float(durations.get("vector", 0)))
        latencies["HYBRID_RRF"].append(sum(float(durations.get(name, 0)) for name in ("bm25", "embedding", "vector", "rrf")))
        latencies["HYBRID_RERANK"].append(elapsed_ms)
        results.append({
            "case": case,
            "documents": {
                name: unique_documents(rank, candidates) for name, rank in ranks.items()
            },
        })
    pipelines = {}
    for name, values in latencies.items():
        item = {}
        for k in (5, 10, 20):
            item.update(retrieval_metrics(results, name, k))
        item.update({
            "p50Ms": percentile(values, 0.50),
            "p95Ms": percentile(values, 0.95),
            "p99Ms": percentile(values, 0.99),
        })
        pipelines[name] = item
    answer_metrics = None
    if args.include_answers:
        answer_cases = [json.loads(line) for line in ANSWER_CASES.read_text(encoding="utf-8").splitlines() if line]
        document_hits = []
        citation_validity = []
        deterministic = []
        for case in answer_cases:
            response = request_json(
                f"{args.base_url}/api/rag/ask",
                "POST",
                {"question": case["question"], "topK": 6},
                token,
            )["data"]
            answer = response.get("answer", "")
            references = response.get("references", [])
            allowed = {reference.get("sourceId") for reference in references}
            markers = {f"S{value}" for value in re.findall(r"\[S(\d+)]", answer)}
            citation_validity.append(1.0 if markers <= allowed else 0.0)
            gold = set(case.get("goldDocuments", []))
            if gold:
                cited_documents = {reference.get("documentName") for reference in references}
                document_hits.append(1.0 if cited_documents & gold else 0.0)
            required = all(term.lower() in answer.lower() for term in case.get("requiredTerms", []))
            unsupported = any(term.lower() in answer.lower() for term in case.get("forbiddenUnsupportedClaims", []))
            has_citation = bool(markers) if case.get("requiresCitation") else True
            deterministic.append(1.0 if required and not unsupported and has_citation else 0.0)
        answer_metrics = {
            "citationDocumentHitRate": statistics.fmean(document_hits) if document_hits else 0.0,
            "citationValidityRate": statistics.fmean(citation_validity) if citation_validity else 0.0,
            "deterministicAnswerPassRate": statistics.fmean(deterministic) if deterministic else 0.0,
            "answerCaseCount": len(answer_cases),
        }
    now = dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")
    report = {
        "configuration": {
            "gitCommit": git_commit(),
            "timestamp": now,
            "chunkStrategyVersion": "structure-v1",
            "chunkTargetTokens": 500,
            "chunkOverlapTokens": 80,
            "embeddingModel": "text-embedding-3-small",
            "embeddingDimensions": 1536,
            "esIndexAlias": "ops_knowledge_chunk_read",
            "analyzer": "smartcn",
            "bm25Size": 50,
            "vectorK": 50,
            "numCandidates": 100,
            "rrfConstant": 60,
            "rerankModel": "BAAI/bge-reranker-v2-m3 or NoOp",
            "rerankTopN": 6,
            "contextMaxTokens": 6000,
        },
        "caseCount": sum(not case.get("noAnswer", False) for case in cases),
        "noAnswerCount": sum(case.get("noAnswer", False) for case in cases),
        "pipelines": pipelines,
        "answerMetrics": answer_metrics,
        "cases": results,
    }
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = ROOT / "rag-eval" / "result" / f"rag-eval-{stamp}.json"
    md_path = ROOT / "rag-eval" / "report" / f"rag-eval-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(markdown(report), encoding="utf-8")
    print(json_path)
    print(md_path)


if __name__ == "__main__":
    main()
